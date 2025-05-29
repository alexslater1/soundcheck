#!/usr/bin/env bash
# What of the local dev setup is running: containers, the services started in terminals, build daemons, phone.
#   ops/scripts/local-status.sh               full report (`make status`)
#   ops/scripts/local-status.sh --leftovers   only what is still running, with how to stop it (end of `make down`)
# Read-only, and always exits 0. It does not start an adb server that is not already running.
set -uo pipefail
cd "$(dirname "$0")/../.."
MODE=${1:-full}
left=0

section() { [ "$MODE" = full ] && printf '\n\033[1m%s\033[0m\n' "$1"; return 0; }
leftover() { left=1; printf '  \033[33m%s\033[0m\n' "$1"; }

# The process listening on a TCP port, as "name (pid N)", or nothing.
listener() {
  ss -ltnpH "sport = :$1" 2>/dev/null | grep -o 'users:(("[^"]*",pid=[0-9]*' | head -1 | sed -E 's/users:\(\("([^"]*)",pid=([0-9]*)/\1 (pid \2)/'
}

[ "$MODE" = full ] || echo "Checking for anything still running…"

section "Containers (make up / make down)"
containers=$(docker compose --profile full ps --format '{{.Service}}\t{{.Status}}' 2>/dev/null || true)
if [ -z "$containers" ]; then
  [ "$MODE" = full ] && echo "  none"
else
  while IFS=$'\t' read -r svc st; do
    if [ "$MODE" = full ]; then printf '  %-12s %s\n' "$svc" "$st"; else leftover "container $svc: $st  → make down"; fi
  done <<<"$containers"
fi

section "Services started in terminals"
# name, port, health URL, how to stop it
while IFS='|' read -r name port url stop; do
  who=$(listener "$port")
  if [ -z "$who" ]; then
    [ "$MODE" = full ] && printf '  %-8s :%-5s not running\n' "$name" "$port"
    continue
  fi
  code=$(curl -s -m 3 -o /dev/null -w '%{http_code}' "$url" || true)
  if [ "$MODE" = full ]; then
    printf '  %-8s :%-5s %s, HTTP %s  [%s]\n' "$name" "$port" "$([ "$code" = 200 ] && echo up || echo 'not healthy')" "$code" "$who"
  else
    leftover "$name still listening on :$port [$who]  → $stop"
  fi
done <<'EOF'
api|8080|http://localhost:8080/actuator/health|Ctrl+C in the make api terminal
worker|8090|http://localhost:8090/actuator/health|Ctrl+C in the make worker terminal
expo|8081|http://localhost:8081/status|Ctrl+C in the make app / make android terminal
EOF

section "Build daemons (Gradle keeps them after a build; idle ones exit after ~3 h)"
daemons=$(ps -eo pid=,rss=,etime=,args= | awk '
  /org\.gradle\.launcher\.daemon\.bootstrap\.GradleDaemon/ {
    v = "?"; if (match($0, /gradle-daemon-main-[0-9.]+\.jar/)) v = substr($0, RSTART + 19, RLENGTH - 23)
    printf "Gradle %s daemon\t%s\t%d\t%s\n", v, $1, $2 / 1024, $3; next }
  /org\.jetbrains\.kotlin\.daemon\.KotlinCompileDaemon/ { printf "Kotlin compile daemon\t%s\t%d\t%s\n", $1, $2 / 1024, $3 }')
if [ -z "$daemons" ]; then
  [ "$MODE" = full ] && echo "  none"
else
  while IFS=$'\t' read -r kind pid mb age; do
    if [ "$MODE" = full ]; then printf '  %-22s pid %-8s %5s MB  running %s\n' "$kind" "$pid" "$mb" "$age"; else leftover "$kind (pid $pid, $mb MB)  → make stop-daemons"; fi
  done <<<"$daemons"
fi

section "Phone (make phone / make android)"
if ! command -v adb >/dev/null 2>&1; then
  [ "$MODE" = full ] && echo "  adb not installed"
elif ! pgrep -x adb >/dev/null; then
  [ "$MODE" = full ] && echo "  adb server not running"
elif [ "$MODE" = full ]; then
  devices=$(adb devices | tail -n +2 | grep -v '^$' || true)
  if [ -z "$devices" ]; then echo "  no phone connected"; else
    echo "$devices" | sed 's/^/  /'
    forwards=$(adb reverse --list 2>/dev/null || true)
    if [ -z "$forwards" ]; then echo "  no port forwards: run make phone"; else echo "$forwards" | sed 's/^/  reverse /'; fi
  fi
fi

if [ "$MODE" != full ]; then
  if [ $left = 0 ]; then echo "  Nothing left running."; fi
  # The adb server is tiny and shared with Android Studio, so make down leaves it alone; say so once.
  if pgrep -x adb >/dev/null; then echo "  (adb server still running: harmless; adb kill-server stops it)"; fi
fi
exit 0
