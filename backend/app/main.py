"""Dot's planning service.

One endpoint that matters: given what is on somebody's screen and what they
asked for, return the single next thing to tell them. The phone does the
seeing, the validating and the drawing; this does the deciding.

What it deliberately does not do: log the goal, the screen, or the image. The
default trace says how many controls arrived and which kind of decision went
back, and nothing else.
"""

from __future__ import annotations

import asyncio
import logging
import math
import time
import uuid

from fastapi import Depends, FastAPI, HTTPException, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from pydantic import BaseModel, Field

from . import billing, failures, usage
from .preview import PreviewConfig
from .config import SETTINGS
from .failures import Failure
from .ledger import Ledger, LedgerError, PlusPeriod
from .provider import ProviderRefused, ProviderUnavailable, build_provider
from .schemas import NextStepRequest, NextStepResponse
from .security import Window, limit_registration, rate_limit, require_caller, require_client_token

log = logging.getLogger("dot")

app = FastAPI(title="Dot planning service", version="1.0", docs_url=None, redoc_url=None)

_provider = None
_provider_error: str | None = None

# Set only by tests, so the whole service can be exercised without a key or a
# network. Startup leaves it alone; nothing in production ever assigns it.
_provider_override = None


def set_provider_for_tests(provider) -> None:
    global _provider_override
    _provider_override = provider


def _current_provider():
    return _provider_override if _provider_override is not None else _provider


LEDGER = Ledger(SETTINGS.db_path)
ENTITLEMENTS = billing.ENTITLEMENTS


def set_billing_for_tests(ledger: Ledger | None = None, entitlements=None, preview=None) -> None:
    global LEDGER, ENTITLEMENTS, PREVIEW
    if ledger is not None:
        LEDGER = ledger
    if entitlements is not None:
        ENTITLEMENTS = entitlements
    if preview is not None:
        PREVIEW = preview
        PREVIEW_TRIES_PER_INSTALL._events.clear()
        PREVIEW_TRIES_PER_ADDRESS._events.clear()


# What each ledger refusal is, over HTTP, and whether trying again helps.
_LEDGER_STATUS = {
    "unknown_identity": (status.HTTP_401_UNAUTHORIZED, False),
    "not_admitted": (status.HTTP_403_FORBIDDEN, False),
    "bad_task": (status.HTTP_400_BAD_REQUEST, False),
    "no_allowance": (status.HTTP_402_PAYMENT_REQUIRED, False),
    "task_limit": (status.HTTP_429_TOO_MANY_REQUESTS, False),
    "daily_limit": (status.HTTP_429_TOO_MANY_REQUESTS, False),
}


def ledger_refusal(exc: LedgerError, correlation_id: str) -> HTTPException:
    code, retryable = _LEDGER_STATUS.get(exc.code, (status.HTTP_400_BAD_REQUEST, False))
    return HTTPException(
        code,
        {"category": exc.code, "source": failures.SOURCE_DOT, "retryable": retryable, "retry_after_s": None,
         "reason": "", "correlation_id": correlation_id},
    )


def require_identity(request: Request) -> str:
    """The person making the request: their id, proved by the secret only their install holds."""
    try:
        return LEDGER.authenticate(request.headers.get("x-dot-user"), request.headers.get("x-dot-key"))
    except LedgerError as exc:
        raise ledger_refusal(exc, getattr(request.state, "correlation_id", "")) from exc


@app.on_event("startup")
def _start() -> None:
    global _provider, _provider_error
    try:
        _provider = build_provider()
        _provider_error = None
    except ProviderUnavailable as exc:
        _provider, _provider_error = None, exc.category
    logging.basicConfig(level=logging.INFO)
    log.info(
        "dot planning service ready (model=%s, provider=%s, auth=%s)",
        SETTINGS.model,
        "configured" if _provider else "unconfigured",
        "on" if SETTINGS.client_token else "MISSING",
    )


@app.middleware("http")
async def _limit_body(request: Request, call_next):
    """Refuse an oversized body before it is parsed, and label every exchange.

    The correlation id is random and means nothing on its own. It goes back to
    the phone with every answer and every failure, and into this service's
    log beside the failure's category, so one attempt can be followed from
    the phone to here without either side recording what was on the screen.
    """
    request.state.correlation_id = uuid.uuid4().hex[:12]
    declared = request.headers.get("content-length")
    if declared and declared.isdigit() and int(declared) > SETTINGS.max_body_bytes:
        log.warning("request refused category=request_rejected reason=too_large cid=%s", request.state.correlation_id)
        response = JSONResponse(
            {"detail": failures.TOO_LARGE.body(request.state.correlation_id)},
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
        )
    else:
        response = await call_next(request)
    response.headers["X-Dot-Correlation"] = request.state.correlation_id
    return response


@app.exception_handler(RequestValidationError)
async def _refused_shape(request: Request, exc: RequestValidationError) -> JSONResponse:
    """A request out of the agreed shape, said in the phone's vocabulary.

    FastAPI's own answer echoes the offending values back, which would put
    screen text in a response and tell the phone nothing it can act on. This
    names only where and what kind: field paths and error types, never a
    value. The phone treats it as a refusal of this request, not as a
    connection problem to retry.
    """
    cid = getattr(request.state, "correlation_id", "")
    where = sorted({
        ".".join(str(p) for p in e.get("loc", ()) if not isinstance(p, int)) + ":" + str(e.get("type", ""))
        for e in exc.errors()
    })[:8]
    log.warning("request refused category=request_rejected reason=validation fields=%s cid=%s", ",".join(where), cid)
    return JSONResponse(
        {"detail": {**failures.REQUEST_REJECTED.body(cid), "fields": where}},
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
    )


@app.exception_handler(StarletteHTTPException)
async def _http_failure(request: Request, exc: StarletteHTTPException) -> JSONResponse:
    """Every refusal with a category the phone can act on, even the plain ones."""
    cid = getattr(request.state, "correlation_id", "")
    detail = exc.detail
    if not isinstance(detail, dict):
        category = {
            status.HTTP_401_UNAUTHORIZED: "unauthorized",
            status.HTTP_404_NOT_FOUND: "not_found",
            status.HTTP_413_REQUEST_ENTITY_TOO_LARGE: "request_rejected",
            status.HTTP_503_SERVICE_UNAVAILABLE: "not_configured",
        }.get(exc.status_code, "service_error")
        detail = {
            "category": category, "source": failures.SOURCE_DOT, "retryable": False,
            "retry_after_s": None, "reason": "", "correlation_id": cid,
        }
    return JSONResponse({"detail": detail}, status_code=exc.status_code, headers=exc.headers)


async def _unless_abandoned(work, request: Request, poll_s: float = 0.25):
    """Run [work], but stop it the moment the phone hangs up.

    The phone abandons a request when the screen moves under it, and it
    disconnects when it does. Carrying on would pay for an answer nobody will
    read and, under a limit, crowd out the one that replaces it.
    """
    task = asyncio.ensure_future(work)
    try:
        while True:
            done, _ = await asyncio.wait({task}, timeout=poll_s)
            if done:
                return task.result()
            if await request.is_disconnected():
                task.cancel()
                raise ProviderUnavailable(failures.CLIENT_GONE)
    finally:
        if not task.done():
            task.cancel()


@app.get("/healthz")
def healthz() -> dict:
    """Liveness, and what is configured. No secrets in the answer.

    Configuration only: a key being present says nothing about whether it
    works. `provider` names what will actually answer, so a development stub
    can never be mistaken for the model.
    """
    provider = _current_provider()
    return {
        "ok": True,
        "model": SETTINGS.model,
        "provider": getattr(provider, "name", None) if provider else None,
        "provider_configured": provider is not None,
        "auth_configured": SETTINGS.client_token is not None or SETTINGS.install_auth,
        "install_auth": SETTINGS.install_auth,
    }


# What each kind of failure is, over HTTP. Dot's own limiter is the only 429:
# a provider's limit is the provider's problem, not the caller's.
_STATUS = {
    "local_rate_limit": status.HTTP_429_TOO_MANY_REQUESTS,
    "timeout": status.HTTP_504_GATEWAY_TIMEOUT,
    "provider_throttled": status.HTTP_503_SERVICE_UNAVAILABLE,
    "provider_limited": status.HTTP_503_SERVICE_UNAVAILABLE,
    "provider_capacity": status.HTTP_503_SERVICE_UNAVAILABLE,
    "quota_exhausted": status.HTTP_503_SERVICE_UNAVAILABLE,
    "not_configured": status.HTTP_503_SERVICE_UNAVAILABLE,
}


def failure_response(failure: Failure, correlation_id: str) -> HTTPException:
    """A failure the phone can act on: what kind, whether to retry, when. Never content."""
    headers = {}
    if failure.retry_after_s is not None:
        headers["Retry-After"] = str(max(1, math.ceil(failure.retry_after_s)))
    return HTTPException(
        _STATUS.get(failure.category, status.HTTP_502_BAD_GATEWAY),
        failure.body(correlation_id),
        headers=headers or None,
    )


# ---------------------------------------------------------------- accounts


@app.post("/v1/identity", dependencies=[Depends(require_caller), Depends(limit_registration), Depends(rate_limit)])
def create_identity() -> dict:
    """A new person: an id, and a secret the phone keeps and nobody else is given."""
    user_id, key = LEDGER.register()
    return {"user_id": user_id, "key": key}


async def _plus_for(user_id: str, refresh: bool = False) -> PlusPeriod:
    """Plus as RevenueCat says, or a preview this service granted. A paid
    subscription always wins; a preview is never presented as a purchase."""
    paid = await ENTITLEMENTS.status(user_id, refresh=refresh)
    preview = LEDGER.preview(user_id)
    if paid.active and not paid.promotional:
        return paid
    if preview is not None:
        return preview
    return paid


async def _allowance_body(user_id: str, refresh: bool = False) -> dict:
    plus = await _plus_for(user_id, refresh=refresh)
    body = LEDGER.allowance(user_id, plus).body()
    # refresh_min_s: a forced refresh sooner than this gets the cached answer,
    # so the phone spaces its purchase confirmations at least this far apart.
    return {**body, "billing_configured": ENTITLEMENTS.configured, "preview_available": PREVIEW.open(),
            "refresh_min_s": billing.MIN_REFRESH_S}


@app.get("/v1/allowance", dependencies=[Depends(require_caller)])
async def allowance(user_id: str = Depends(require_identity)) -> dict:
    return await _allowance_body(user_id)


@app.post("/v1/entitlement/refresh", dependencies=[Depends(require_caller), Depends(rate_limit)])
async def refresh_entitlement(user_id: str = Depends(require_identity)) -> dict:
    """After a purchase or restore: asked of RevenueCat again, not taken from the phone."""
    return await _allowance_body(user_id, refresh=True)


class TaskStart(BaseModel):
    task_id: str = Field(min_length=8, max_length=64, pattern=r"^[A-Za-z0-9_-]+$")
    kind: str = Field(pattern=r"^(ask|lesson)$")
    course_id: str = Field(default="", max_length=40)


@app.post("/v1/tasks", dependencies=[Depends(require_caller), Depends(rate_limit)])
async def start_task(body: TaskStart, request: Request, user_id: str = Depends(require_identity)) -> dict:
    """Admit a task, once: the same task id again is the same answer, charged once."""
    plus = await _plus_for(user_id)
    try:
        admission, allowance_now = LEDGER.admit(user_id, body.task_id, body.kind, plus, body.course_id)
    except LedgerError as exc:
        raise ledger_refusal(exc, request.state.correlation_id) from exc
    log.info("task admission kind=%s admitted=%s source=%s replay=%s reason=%s cid=%s",
             body.kind, admission.admitted, admission.source or "-", admission.replay, admission.reason or "-",
             request.state.correlation_id)
    return {
        "admitted": admission.admitted,
        "source": admission.source,
        "reason": admission.reason,
        "replay": admission.replay,
        "allowance": {**allowance_now.body(), "billing_configured": ENTITLEMENTS.configured},
    }


# ------------------------------------------------------------ preview access

PREVIEW = PreviewConfig()
PREVIEW_TRIES_PER_INSTALL = Window(5, 3600.0)
PREVIEW_TRIES_PER_ADDRESS = Window(15, 3600.0)


class PreviewRedeem(BaseModel):
    code: str = Field(min_length=4, max_length=64)


def _preview_refusal(code: str, http: int, cid: str) -> HTTPException:
    log.warning("preview refused category=%s cid=%s", code, cid)
    return HTTPException(http, {"category": code, "correlation_id": cid})


@app.post("/v1/preview/redeem", dependencies=[Depends(require_caller)])
async def redeem_preview(body: PreviewRedeem, request: Request, user_id: str = Depends(require_identity)) -> dict:
    """A preview code, for the install that is signed in: seven days of Plus,
    once. The code is checked here, against a hash only this service holds."""
    cid = request.state.correlation_id
    if not PREVIEW.open():
        raise _preview_refusal("preview_off", status.HTTP_404_NOT_FOUND, cid)
    address = request.client.host if request.client else "unknown"
    if not PREVIEW_TRIES_PER_INSTALL.allow(user_id) or not PREVIEW_TRIES_PER_ADDRESS.allow(address):
        raise _preview_refusal("preview_rate_limited", status.HTTP_429_TOO_MANY_REQUESTS, cid)
    if not PREVIEW.matches(body.code):
        raise _preview_refusal("preview_code_invalid", status.HTTP_403_FORBIDDEN, cid)
    if LEDGER.preview(user_id) is None and LEDGER.preview_count() >= PREVIEW.max_grants:
        raise _preview_refusal("preview_exhausted", status.HTTP_403_FORBIDDEN, cid)
    try:
        grant, new = LEDGER.grant_preview(user_id, PREVIEW.days, via="revenuecat" if ENTITLEMENTS.configured else "ledger")
    except LedgerError as exc:
        raise _preview_refusal(exc.code, status.HTTP_403_FORBIDDEN, cid) from exc
    at_revenuecat = False
    if ENTITLEMENTS.configured:
        # RevenueCat's own promotional entitlement, so the app's CustomerInfo
        # shows dot_plus too. Asked again on a repeat redemption: granting
        # the same end time again is harmless, and a first attempt may have failed.
        at_revenuecat = await ENTITLEMENTS.grant_promotional(user_id, int((grant.expires_at or 0) * 1000))
    log.info("preview granted new=%s revenuecat=%s cid=%s", new, at_revenuecat, cid)
    return {
        "preview": {"expires_at": grant.expires_at, "new": new, "revenuecat": at_revenuecat},
        "allowance": await _allowance_body(user_id, refresh=True),
    }


@app.post("/v1/preview/revoke", dependencies=[Depends(require_caller), Depends(rate_limit)])
async def revoke_preview(request: Request, user_id: str = Depends(require_identity)) -> dict:
    """Ends the install's preview now, here and at RevenueCat."""
    revoked = LEDGER.revoke_preview(user_id)
    at_revenuecat = await ENTITLEMENTS.revoke_promotionals(user_id) if ENTITLEMENTS.configured else False
    log.info("preview revoked=%s revenuecat=%s cid=%s", revoked, at_revenuecat, request.state.correlation_id)
    return {"revoked": revoked, "allowance": await _allowance_body(user_id, refresh=True)}


@app.post("/v1/tasks/{task_id}/release", dependencies=[Depends(require_caller)])
async def release_task(task_id: str, request: Request, user_id: str = Depends(require_identity)) -> dict:
    """The task ended before Dot helped with it: its credit comes back. Never after Dot helped."""
    try:
        released = LEDGER.release(user_id, task_id)
    except LedgerError as exc:
        raise ledger_refusal(exc, request.state.correlation_id) from exc
    return {"released": released, "allowance": await _allowance_body(user_id)}


@app.post("/v1/tasks/{task_id}/finish", dependencies=[Depends(require_caller)])
def finish_task(task_id: str, user_id: str = Depends(require_identity)) -> dict:
    LEDGER.finish(user_id, task_id)
    return {"ok": True}


@app.get("/v1/usage", dependencies=[Depends(require_client_token)])
def usage_report() -> dict:
    """Per-session counts and estimated cost, for the developer. No content."""
    pricing = usage.pricing_for(SETTINGS.model)
    return {
        "model": SETTINGS.model,
        "pricing": None if pricing is None else pricing.__dict__,
        **usage.LEDGER.snapshot(),
    }


@app.post(
    "/v1/next-step",
    response_model=NextStepResponse,
    dependencies=[Depends(require_caller), Depends(rate_limit)],
)
async def next_step(body: NextStepRequest, request: Request) -> NextStepResponse:
    correlation_id = request.state.correlation_id
    if len(body.observation.candidates) > SETTINGS.max_candidates:
        raise HTTPException(status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, "Too many controls.")

    provider = _current_provider()
    if provider is None:
        raise failure_response(failures.NOT_CONFIGURED, correlation_id)

    # Every request belongs to a person's admitted task, and is counted
    # against it — a guard against loops, never a charge.
    task_id = None
    # Without the shared token, an install's own identity is the only way in.
    if SETTINGS.require_identity or not getattr(request.state, "token_ok", False):
        user_id = require_identity(request)
        task_id = request.headers.get("x-dot-task") or ""
        try:
            LEDGER.count_request(user_id, task_id)
        except LedgerError as exc:
            log.warning("request refused category=%s cid=%s", exc.code, correlation_id)
            raise ledger_refusal(exc, correlation_id) from exc

    started = time.monotonic()
    meter = usage.RequestMeter(
        session=usage.session_key(body.session_id),
        trigger=body.trigger,
        image=body.screenshot_jpeg_b64 is not None,
    )
    token = usage.CURRENT.set(meter)
    ok = False
    try:
        decision = await _unless_abandoned(provider.decide(body), request)
        ok = True
        # Dot has helped with this task: its credit is spent. A task that
        # only ever got "this can't be done here" is not charged.
        if task_id and decision.kind != "blocked":
            LEDGER.mark_useful(task_id)
    except ProviderRefused as exc:
        # The model answered with something unusable, twice. Worth another go
        # from the phone, later, against a fresh screen; not worth pretending
        # it was a decision.
        log.warning("provider failure category=unusable_answer cid=%s", correlation_id)
        raise failure_response(failures.UNUSABLE, correlation_id) from exc
    except ProviderUnavailable as exc:
        f = exc.failure
        # Fixed fields only: never the provider's message, never the request.
        log.warning(
            "provider failure category=%s reason=%s retryable=%s retry_after_s=%s cid=%s",
            f.category, f.reason or "-", f.retryable, f.retry_after_s, correlation_id,
        )
        raise failure_response(f, correlation_id) from exc
    finally:
        usage.CURRENT.reset(token)
        summary = meter.summary(SETTINGS.model)
        usage.LEDGER.add(summary, ok)
        # Counts only. Never the goal, the screen or the picture.
        log.info(
            "usage session=%s trigger=%s image=%s attempts=%d prompt=%s cached=%s output=%s "
            "thinking=%s latency_ms=%d est_usd=%s cid=%s",
            summary["session"], summary["trigger"], summary["image"], summary["attempts"],
            summary["prompt_tokens"], summary["cached_tokens"], summary["output_tokens"],
            summary["thinking_tokens"], summary["latency_ms"],
            summary["estimated_usd"] if summary["estimated_usd"] is not None else "unknown",
            correlation_id,
        )

    latency_ms = int((time.monotonic() - started) * 1000)
    if SETTINGS.verbose:
        log.info(
            "decided kind=%s assessment=%s candidates=%d information=%d named=%s image=%s latency_ms=%d",
            decision.kind,
            decision.assessment,
            len(body.observation.candidates),
            len(body.observation.information),
            bool(getattr(decision, "candidate_id", None)),
            bool(body.screenshot_jpeg_b64),
            latency_ms,
        )
    return NextStepResponse(
        decision=decision,
        model=SETTINGS.model,
        provider=getattr(provider, "name", "unknown"),
        latency_ms=latency_ms,
    )
