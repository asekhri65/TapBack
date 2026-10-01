"""One real guided request against the running service, end to end.

Registers a throwaway identity, admits one task, and asks for the next step
on a small, made-up Settings screen — the same path the phone takes. Prints
only statuses, the decision's kind, timings and correlation ids: never a key,
a token, or anything the model said.

    backend/dev.sh check

Costs one model request. The throwaway identity and its task stay in the
ledger (backend/data/), apart from anyone's real identity.
"""

from __future__ import annotations

import argparse
import os
import sys
import time
import uuid

import httpx


def screen() -> dict:
    return {
        "observation_id": "check-" + uuid.uuid4().hex[:8],
        "package_name": "com.android.settings",
        "window_title": "Settings",
        "screen_width": 1080,
        "screen_height": 2400,
        "candidates": [
            {"id": "c1", "label": "Network & internet", "role": "row", "bounds": [0, 300, 1080, 450], "clickable": True},
            {"id": "c2", "label": "Display", "role": "row", "bounds": [0, 450, 1080, 600], "clickable": True},
            {"id": "c3", "label": "Sound & vibration", "role": "row", "bounds": [0, 600, 1080, 750], "clickable": True},
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", default="8000")
    args = parser.parse_args()
    base = f"http://127.0.0.1:{args.port}"
    token = os.environ.get("DOT_CLIENT_TOKEN", "")
    auth = {"Authorization": f"Bearer {token}"} if token else {}

    def step(name: str, r: httpx.Response, started: float) -> None:
        cid = r.headers.get("x-dot-correlation", "-")
        print(f"  {name:<12} HTTP {r.status_code}  {int((time.monotonic() - started) * 1000)} ms  cid {cid}")

    with httpx.Client(base_url=base, timeout=30) as c:
        t = time.monotonic()
        h = c.get("/healthz")
        step("health", h, t)
        provider = h.json().get("provider")
        if provider != "gemini":
            print(f"FAIL: the running service's provider is {provider!r}, not the real model.")
            return 1

        t = time.monotonic()
        r = c.post("/v1/identity", headers=auth)
        step("identity", r, t)
        if r.status_code != 200:
            print("FAIL: could not register an identity:", r.json().get("detail", {}).get("category"))
            return 1
        who = {**auth, "X-Dot-User": r.json()["user_id"], "X-Dot-Key": r.json()["key"]}

        task = "check" + uuid.uuid4().hex[:16]
        t = time.monotonic()
        r = c.post("/v1/tasks", json={"task_id": task, "kind": "ask"}, headers=who)
        step("admission", r, t)
        if r.status_code != 200 or not r.json().get("admitted"):
            print("FAIL: task not admitted:", r.json())
            return 1

        body = {"session_id": "check-" + uuid.uuid4().hex[:8], "goal": "make the text bigger", "observation": screen(), "trigger": "start"}
        t = time.monotonic()
        r = c.post("/v1/next-step", json=body, headers={**who, "X-Dot-Task": task})
        step("next-step", r, t)
        if r.status_code != 200:
            print("FAIL: planning failed:", r.json().get("detail", {}).get("category"))
            return 1
        decision = r.json().get("decision", {})
        print(f"  decision     kind={decision.get('kind')} candidate={decision.get('candidate_id', '-')}")
        print("OK: a real admitted task was planned by the live model.")
        return 0


if __name__ == "__main__":
    sys.exit(main())
