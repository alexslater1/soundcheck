#!/usr/bin/env bash
# `make dev`: the whole local stack from one terminal.
#   1. containers (make up), 2. the API and the transcode worker in the background, their logs in
#   .localdata/logs/, 3. the app in the foreground, so Expo's keys (r reload, j debugger…) still work:
#   `make dev` → the Expo dev server (web on :8088, the installed phone app through make phone);
#   `make dev ANDROID=1` → rebuild and install the Android dev build first (after native changes).
# Ctrl+C stops the app, the API and the worker. The containers keep running: `make down` stops them
# (and saves the clips, and stops the build daemons).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LOGS="$ROOT/.localdata/logs"
mkdir -p "$LOGS"
cd "$ROOT"

say() { printf '\033[1m%s\033[0m\n' "$*"; }

for port in 8080 8090 8081; do
  if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
    say "Port $port is already in use: something from an earlier run is still going. Run 'make status'."
    exit 1
  fi
done

say "1/3 Containers (make up)…"
make --no-print-directory up >/dev/null 2>&1 || { say "make up failed: run it on its own to see why"; exit 1; }
say "    db, localstack, lgtm, edge: healthy"

pids=()
stop() { # stop <exit code>: 0 after Ctrl+C (how this is meant to end), the failure's code otherwise
  local code="$1"
  trap - INT TERM EXIT
  say ""
  say "Stopping the API and the worker…"
  for pid in "${pids[@]}"; do
    # Each service runs in its own process group (setsid): stop the whole group (make, Gradle, the JVM).
    kill -TERM -- "-$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
  say "Stopped. The containers are still up: 'make down' when you are finished."
  exit "$code"
}
trap 'stop 0' INT TERM
trap 'stop $?' EXIT

start() { # start <name> <make target>
  setsid make --no-print-directory "$2" >"$LOGS/$1.log" 2>&1 &
  pids+=("$!")
}

wait_for() { # wait_for <name> <url> <seconds>
  for _ in $(seq 1 "$3"); do
    if curl -sf -o /dev/null "$2"; then
      say "    $1 ready"
      return 0
    fi
    sleep 1
  done
  say "    $1 did not start within $3 s; see $LOGS/$1.log"
  return 1
}

say "2/3 API and worker (logs: .localdata/logs/api.log, worker.log)…"
start api api
start worker worker
wait_for api http://localhost:8080/actuator/health 240
wait_for worker http://localhost:8090/actuator/health 240

if [ "${ANDROID:-0}" = "1" ]; then
  say "3/3 Building and installing the Android dev build (make android); it then stays as the bundler."
  make --no-print-directory android
else
  if adb get-state >/dev/null 2>&1; then
    make --no-print-directory phone >/dev/null && say "    phone connected: ports forwarded"
  fi
  say "3/3 Expo dev server: web at http://localhost:8088, phone through the installed dev build."
  cd "$ROOT/app" && npx expo start
fi
