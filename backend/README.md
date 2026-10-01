# Dot planning service

Given what is on somebody's screen and what they asked for, this returns the
one next thing to tell them. The phone does the seeing, the validating and the
drawing; this does the deciding.

## What it does not do

It does not log the goal, the screen contents, or the screenshot. With
`DOT_VERBOSE=1` it logs how many controls arrived and which kind of decision
went back — counts, not content. Nothing is written to disk.

## Provider and model

`google-genai`, the official Python SDK, through `client.models.generate_content`.

The current quickstart shows the newer Interactions API, but the installed SDK
emits `UserWarning: Interactions usage is experimental and may change in future
versions`, so this uses the stable surface instead. It supports what is needed:
a system instruction, inline image parts, and `response_json_schema` for
structured answers.

Default model `gemini-3.5-flash` — listed stable and image-capable. Change it
with `GEMINI_MODEL`; `gemini-3.5-flash-lite` is cheaper, `gemini-3.8-flash` is
the newest stable.

Checked against:
- https://ai.google.dev/gemini-api/docs/models
- https://ai.google.dev/gemini-api/docs/structured-output
- https://ai.google.dev/gemini-api/docs/image-understanding
- https://ai.google.dev/gemini-api/docs/quickstart

## Setup

```bash
cd backend
python3 -m venv .venv
./.venv/bin/pip install -r requirements-dev.txt

cp .env.example .env
# Open .env and put your key in it. Nothing else needs editing.
#   GEMINI_API_KEY   from https://aistudio.google.com/apikey
#   DOT_CLIENT_TOKEN python3 -c "import secrets; print(secrets.token_urlsafe(32))"
```

`.env` is gitignored. The key belongs there or in deployment secrets, and
nowhere else — not in the app, not in Gradle, not in a chat message.

## Run

```bash
backend/dev.sh start    # starts once, in the background; never a second copy
backend/dev.sh status   # running? the real model or the stub? can the emulator reach it?
backend/dev.sh check    # one real guided request end to end (identity, admission, planning)
backend/dev.sh stop
```

`dev.sh` writes its pid and log to `backend/data/` (not in git). If port 8000
is taken by something it did not start, it says so and does not start a
second server. When the app says "TapBack's guidance service isn't
answering", run `backend/dev.sh status`: "NOT RUNNING" with the last log
lines means the service has exited. In a debug build, Settings → Developer
shows the address the app uses, a connection check, and the recent requests
(endpoint, phase, time, status, category, correlation id; never content).

Running it by hand is the same service:

```bash
cd backend
set -a && . ./.env && set +a
./.venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000
```

Loopback only. The Android emulator's `10.0.2.2` is the host's loopback, so
the emulator reaches it without the server listening on your network. Binding
`0.0.0.0` would expose a development server, with a shared token, to anyone on
the same Wi-Fi. For a physical phone use `adb reverse tcp:8000 tcp:8000` and
`dot.backend.url=http://127.0.0.1:8000` rather than opening the port.

```bash
curl -s localhost:8000/healthz
# {"ok":true,"model":"gemini-3.5-flash","provider":"gemini","provider_configured":true,"auth_configured":true}
```

That is configuration only: it does not prove the key works. `provider` is
`"stub"` when `tests/stub_server.py` is what is running. To prove a live model
call, send one synthetic request (see "Live check" below).
```

## Connecting the emulator

The emulator reaches the host machine at `10.0.2.2`. The debug app reads both
values from `local.properties`, which is gitignored:

```properties
dot.backend.url=http://10.0.2.2:8000
dot.backend.token=<the same DOT_CLIENT_TOKEN>
```

On an Android 17 (API 37) emulator or phone the debug app also has to be
allowed to reach a private address: it asks ("find, connect to, and determine
the relative position of nearby devices") the first time it opens. Without that
permission every request to `10.0.2.2` times out after 8 seconds, which looks
exactly like a backend that is down while the backend log shows nothing at all.
If you denied it: `adb shell pm grant com.aaravsekhri.dot android.permission.ACCESS_LOCAL_NETWORK`.

Release builds have no cleartext permission and no baked-in token, so neither
value can leak into a shipped app.

## Live check

One synthetic, non-sensitive request through the running server. It reads the
token from `.env` itself and prints no secrets:

```bash
cd backend && ./.venv/bin/python - <<'EOF'
import json, urllib.request
env = dict(l.split("=", 1) for l in open(".env").read().splitlines() if "=" in l and not l.startswith("#"))
body = {"session_id": "check", "goal": "Make the text bigger",
        "observation": {"observation_id": "o1", "package_name": "com.android.settings",
                        "candidates": [{"id": "n1", "label": "Display & touch", "bounds": [0, 900, 1080, 1100], "clickable": True}]}}
req = urllib.request.Request("http://127.0.0.1:8000/v1/next-step", json.dumps(body).encode(),
                             {"authorization": "Bearer " + env["DOT_CLIENT_TOKEN"].strip(), "content-type": "application/json"})
r = json.load(urllib.request.urlopen(req, timeout=60))
print(r["provider"], r["decision"]["kind"], r["decision"].get("candidate_id"), r["latency_ms"], "ms")
EOF
```

`gemini highlight n1 …` means the key, the model and the schema all work. A
failure prints an HTTP error whose body names a category: `auth` (the key),
`model_not_found` (`GEMINI_MODEL`), `rate_limited`, `timeout` or `upstream`.

## Accounts, guided tasks and Dot Plus

Every install gets an identity from `POST /v1/identity`: an id and a secret
the phone keeps. The phone sends both as `X-Dot-User` / `X-Dot-Key` with each
call, beside the shared `DOT_CLIENT_TOKEN`. The id is also the person's
RevenueCat app user id.

A guided task is admitted once with `POST /v1/tasks` (`task_id`, `kind`
`ask|lesson`, `course_id`). The same `task_id` again is the same answer and is
charged once. Every `/v1/next-step` must name its task in `X-Dot-Task`; the
request is counted against the task (a guard against loops, never a charge).
A credit is reserved at admission and spent at the first useful answer; a
task that ends before then is released (`POST /v1/tasks/{id}/release`) and so
is a reservation left unused for six hours. The ledger is SQLite at
`DOT_DB_PATH` (default `backend/data/`, gitignored).

Free: two Ask tasks, once, and the whole Google Maps course; no free Ask task
comes back each week. TapBack Plus (entitlement `dot_plus`): ten new Ask tasks
per subscription week and every course. Lessons never use an Ask task, and a
retried or resumed task is the same task, charged once.

Plus is verified here, never taken from the phone: this service calls
RevenueCat's `GET /v1/subscribers/{id}` with `REVENUECAT_SECRET_KEY`, caches a
verified answer for five minutes, keeps a recent one for six hours if
RevenueCat is unreachable, and allows a forced refresh after a purchase or
restore at most every eight seconds per person.

### Hosting it for release builds

A release build carries no shared token and speaks only HTTPS. Build it with
`tapback.release.backend.url=https://…` in `local.properties` (or the
`TAPBACK_BACKEND_URL` environment variable); a non-HTTPS address fails the
build. Run the service behind a TLS-terminating proxy with:

```
DOT_INSTALL_AUTH=1              # installs authenticate with their own identity
DOT_REGISTRATIONS_PER_HOUR=10   # new identities per client address
GEMINI_API_KEY, REVENUECAT_SECRET_KEY, DOT_DB_PATH on persistent storage
```

With `DOT_INSTALL_AUTH=1`, `/v1/identity` is open to anyone (limited per
address) and every other endpoint requires the install's own id and secret.
`DOT_CLIENT_TOKEN` still works for development and is still required for
`/v1/usage`. Limits of this scheme, before a public launch: registration is
not attested (add Play Integrity), the per-address limit sees the proxy's
address unless the proxy is taught to pass the client's, and the SQLite
ledger suits one instance, not several.

### Setting up RevenueCat (Test Store, for demonstrations)

1. In the RevenueCat dashboard, create a project and, under **Apps &
   providers → Test configuration**, the Test Store. Copy its public SDK key
   (starts `test_`).
2. **Product catalog → Products**: create `dot_plus_weekly`, a 1-week
   subscription at USD 0.99.
3. **Entitlements**: create `dot_plus` and attach `dot_plus_weekly`.
4. **Offerings**: in `default`, add a Weekly package with `dot_plus_weekly`,
   and make `default` current.
5. **Project settings → API keys**: create a secret key (starts `sk_`).
6. Put the secret key in `backend/.env` as `REVENUECAT_SECRET_KEY`, and the
   `test_` key in the project's `local.properties` as
   `revenuecat.test.key=test_…`. Rebuild the debug app and restart this
   service.

Test Store purchases show a dialog with "Test valid purchase", "Test failed
purchase" and "Cancel". A weekly test subscription renews every five minutes,
five times, then ends. The debug build only accepts a `test_` key; the release
build only a Google Play `goog_` key (`revenuecat.google.key`), with real
products in Play Console.

### Preview codes (test set-ups only)

For recording lessons without buying: `./dev.sh preview-code` makes a new code,
prints it once, and writes only its SHA-256 to `backend/.env`
(`DOT_PREVIEW_CODE_SHA256`); restart the service. In a debug build,
TapBack › Settings › Lesson preview (test build) › Redeem preview code.

- The service checks the code against that hash, for the install that is
  signed in (its own identity and key); nothing in the app knows the code.
- A valid code grants seven days of Plus (`DOT_PREVIEW_DAYS`), once per install
  — redeeming again returns the same end date — to at most
  `DOT_PREVIEW_MAX_GRANTS` installs (default 5), optionally only until
  `DOT_PREVIEW_UNTIL` (YYYY-MM-DD). Tries are limited per install and address.
- With `REVENUECAT_SECRET_KEY` set, the grant is also made in RevenueCat as a
  promotional `dot_plus` entitlement ending at the same time, so the app's
  CustomerInfo shows it. Without the key it is honoured by this service alone.
- It is shown as "preview", never as a purchase, and never renews.
- End it in the app ("End preview now", which also revokes RevenueCat's
  promotional grants for that customer), or switch codes off with
  `./dev.sh preview-off`. Never set `DOT_PREVIEW_CODE_SHA256` on a public
  service.

If automated granting at RevenueCat is refused (the log says
`promotional grant refused status=…`), grant it by hand: RevenueCat dashboard ›
Customers › search the install's app user id (the `dot_…` id; Settings ›
Developer shows recent requests but not the id — read it from
`backend/data/dot.sqlite3` `users` or from RevenueCat's customer list after a
purchase attempt) › Entitlements › Grant › `dot_plus`, 7 days.

### Terms, Privacy and what the owner still has to supply

The app carries its own Terms of Service and Privacy Policy
(`app/src/main/res/raw/terms_of_service.txt`, `privacy_policy.txt`), shown
from the paywall and from Settings. They describe what this code and this
service actually do. Before publishing, the owner needs to add what only they
can decide, and keep the in-app copy and any hosted copy the same:

- the publisher's legal name and a contact email (the documents currently
  point to the contact details on the Play listing);
- the governing law / jurisdiction for the Terms;
- a public URL for the Privacy Policy, which Google Play requires in the
  listing and the Data safety form;
- how long the service keeps its per-install task records and short-lived
  request logs, once hosting is chosen;
- a review of both documents by someone qualified.

## Stub provider (development only)

```bash
cd backend && set -a && . ./.env && set +a
./.venv/bin/python -m tests.stub_server          # STUB_DELAY_MS=4000 to make answers late
```

It answers in place of the model so the phone's loop can be driven
deterministically — waits, questions, late answers. `/healthz` reports
`"provider":"stub"` while it runs. Nothing run against it is a model test.

`STUB_FLOWS=1` adds a few rules for the debug fixture's pages
(`FixtureAppsActivity`): "search for X" types X and then sends it, and
Install is tapped, waited on, then Open. It exists to exercise the phone's
own handling — watching a field, noticing Open appear, finishing at an app's
sign-in — without a model.

## Tests

```bash
cd backend
DOT_CLIENT_TOKEN=test-token ./.venv/bin/python -m pytest tests -q
```

These use a fake provider: no key, no network, no cost. They cover auth, rate
limiting, body limits, schema validation and the refusal paths. The live model
is exercised separately, from the phone.
