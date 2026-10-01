#!/usr/bin/env bash
# TapBack's planning service, run on this computer for the Android emulator.
#
#   ./dev.sh start     start it in the background (once; never a second copy)
#   ./dev.sh status    is it running, is it the real service, can the emulator reach it
#   ./dev.sh check     one real guided request, end to end (costs one model call)
#   ./dev.sh logs      follow its log
#   ./dev.sh stop      stop the copy this script started
#   ./dev.sh preview-code  make a new lesson-preview code (hash to .env, code printed once)
#   ./dev.sh preview-off   switch preview codes off
#
# Loopback only: the emulator reaches it as 10.0.2.2. It writes its pid and
# log under backend/data/ (not in git). Nothing here prints a key or token.
set -euo pipefail
cd "$(dirname "$0")"

PORT="${DOT_PORT:-8000}"
DATA="data"
PIDFILE="$DATA/server.pid"
LOG="$DATA/server.log"
ADB="${ADB:-$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")}"
mkdir -p "$DATA"

listener() { lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null | head -1 || true; }
ours() { [ -f "$PIDFILE" ] && [ "$(cat "$PIDFILE")" = "$1" ]; }
health() { curl -s --max-time 3 "http://127.0.0.1:$PORT/healthz" || true; }
field() { python3 -c "import json,sys; d=json.loads(sys.argv[1] or '{}'); print(d.get('$1',''))" "$2" 2>/dev/null || true; }

describe() {
  local h="$1"
  [ -z "$h" ] && { echo "  health:    no answer on 127.0.0.1:$PORT"; return 1; }
  local provider auth
  provider=$(field provider "$h"); auth=$(field auth_configured "$h")
  echo "  health:    ok · model $(field model "$h") · provider ${provider:-none} · auth $auth"
  if [ "$provider" = "stub" ]; then
    echo "  WARNING:   this is the STUB service (tests/stub_server.py), not the real model." >&2; return 2
  fi
  if [ "$provider" != "gemini" ]; then
    echo "  WARNING:   no model provider is configured (check GEMINI_API_KEY in backend/.env)." >&2; return 2
  fi
}

emulator() {
  if [ ! -x "$ADB" ] || ! "$ADB" get-state >/dev/null 2>&1; then
    echo "  emulator:  not connected (skipped)"; return 0
  fi
  # stdin is held open briefly so nc reads the reply before it hangs up.
  if "$ADB" shell "(printf 'GET /healthz HTTP/1.0\r\n\r\n'; sleep 2) | nc -w 5 10.0.2.2 $PORT" 2>/dev/null | grep -q '"ok":true'; then
    echo "  emulator:  reaches http://10.0.2.2:$PORT"
  else
    echo "  emulator:  CANNOT reach http://10.0.2.2:$PORT" >&2; return 1
  fi
}

case "${1:-status}" in
  start)
    pid=$(listener)
    if [ -n "$pid" ]; then
      if ours "$pid"; then
        echo "Already running (pid $pid)."; describe "$(health)" || true; exit 0
      fi
      echo "Port $PORT is already used by another process: $(ps -o comm= -p "$pid" 2>/dev/null) (pid $pid)." >&2
      echo "Not starting a second server. Stop that process yourself, or run with DOT_PORT=<other port>." >&2
      exit 1
    fi
    [ -f .env ] || { echo "backend/.env is missing: copy .env.example to .env and fill it in." >&2; exit 1; }
    [ -x .venv/bin/uvicorn ] || { echo "backend/.venv is missing: python3 -m venv .venv && .venv/bin/pip install -r requirements.txt" >&2; exit 1; }
    set -a; . ./.env; set +a
    echo "--- start $(date '+%Y-%m-%d %H:%M:%S') port $PORT" >> "$LOG"
    nohup ./.venv/bin/uvicorn app.main:app --host 127.0.0.1 --port "$PORT" >> "$LOG" 2>&1 &
    echo $! > "$PIDFILE"
    h=""
    for _ in $(seq 1 40); do
      sleep 0.25
      h=$(health); [ -n "$h" ] && break
      kill -0 "$(cat "$PIDFILE")" 2>/dev/null || break
    done
    if [ -z "$h" ]; then
      echo "The service did not start. Last lines of $LOG:" >&2
      tail -n 15 "$LOG" | grep -viE "key|token|secret" >&2 || true
      rm -f "$PIDFILE"; exit 1
    fi
    echo "Started (pid $(cat "$PIDFILE")), logging to backend/$LOG"
    describe "$h" || true
    emulator || true
    ;;

  status)
    pid=$(listener)
    if [ -z "$pid" ]; then
      echo "NOT RUNNING: nothing is listening on port $PORT."
      if [ -f "$PIDFILE" ]; then
        echo "  It was started by this script (pid $(cat "$PIDFILE")) and has exited. Last lines of $LOG:"
        tail -n 8 "$LOG" | grep -viE "key|token|secret" | sed 's/^/    /' || true
      fi
      echo "  Start it with: backend/dev.sh start"
      exit 1
    fi
    if ours "$pid"; then echo "Running (pid $pid, started by this script)."; else echo "Port $PORT is owned by pid $pid ($(ps -o comm= -p "$pid" 2>/dev/null)), not started by this script."; fi
    rc=0
    describe "$(health)" || rc=$?
    emulator || rc=1
    exit $rc
    ;;

  check)
    [ -n "$(listener)" ] || { echo "Not running. Start it first: backend/dev.sh start" >&2; exit 1; }
    set -a; . ./.env; set +a
    ./.venv/bin/python -m scripts.live_check --port "$PORT"
    ;;

  logs)
    tail -n 40 -f "$LOG"
    ;;

  preview-code)
    # A new preview code for lesson recordings. Only its hash is written, to
    # backend/.env; the code itself is printed here once and kept nowhere.
    # Replaces any earlier code (grants already made keep their end date).
    [ -f .env ] || { echo "backend/.env is missing." >&2; exit 1; }
    code=$(./.venv/bin/python -c "import secrets; a='ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; print('TB-' + '-'.join(''.join(secrets.choice(a) for _ in range(4)) for _ in range(3)))")
    hash=$(./.venv/bin/python -c "from app.preview import hash_code; import sys; print(hash_code(sys.argv[1]))" "$code")
    tmp=$(mktemp)
    grep -v '^DOT_PREVIEW_CODE_SHA256=' .env > "$tmp" || true
    echo "DOT_PREVIEW_CODE_SHA256=$hash" >> "$tmp"
    cat "$tmp" > .env && rm -f "$tmp"
    echo "Preview code: $code"
    echo "  Valid for ${DOT_PREVIEW_DAYS:-7} days after redeeming, for up to ${DOT_PREVIEW_MAX_GRANTS:-5} installs."
    echo "  Restart the service to use it: backend/dev.sh stop && backend/dev.sh start"
      echo "  Redeem in a debug build: TapBack > Settings > Lesson preview (test build) > Redeem preview code."
    ;;

  preview-off)
    [ -f .env ] || exit 0
    tmp=$(mktemp); grep -v '^DOT_PREVIEW_CODE_SHA256=' .env > "$tmp" || true; cat "$tmp" > .env && rm -f "$tmp"
    echo "Preview codes switched off (grants already made run to their end, or revoke them in the app). Restart the service."
    ;;

  stop)
    pid=$(listener)
    if [ -n "$pid" ] && ours "$pid"; then
      kill "$pid"; rm -f "$PIDFILE"; echo "Stopped (pid $pid)."
    elif [ -n "$pid" ]; then
      echo "Port $PORT is owned by pid $pid, which this script did not start. Not stopping it." >&2; exit 1
    else
      rm -f "$PIDFILE"; echo "Not running."
    fi
    ;;

  *)
    sed -n '2,13p' "$0"; exit 2
    ;;
esac
