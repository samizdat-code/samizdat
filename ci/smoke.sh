#!/usr/bin/env bash
# Start a built samizdat binary in an empty directory and check it serves,
# then attach its TUI to it. It loads every native library at startup, so
# this is what proves a release archive runs without the build's checkouts.
# Usage: ci/smoke.sh ./samizdat
set -u
bin="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
port=3799
work=$(mktemp -d)
cd "$work"
# a HOME of its own, so no config on the runner is read
export HOME="$work/home"
mkdir -p "$HOME"

HARNESS_PORT=$port HARNESS_DB="$work/smoke.sqlite3" "$bin" --headless > server.log 2>&1 &
pid=$!
ok=0
for _ in $(seq 1 60); do
  if curl -sf "http://127.0.0.1:${port}/health" | grep -q '"status":"ok"'; then ok=1; break; fi
  kill -0 "$pid" 2>/dev/null || break
  sleep 2
done

# The TUI under a pseudo-terminal for a few seconds: it binds the FTXUI shim
# the binary links in, and draws its first frame
tui=0
if [ "$ok" = 1 ]; then
  if [ "$(uname)" = Darwin ]; then
    script -q tui.log "$bin" --connect "http://127.0.0.1:${port}" < /dev/null > /dev/null 2>&1 &
  else
    script -q -c "'$bin' --connect http://127.0.0.1:${port}" tui.log < /dev/null > /dev/null 2>&1 &
  fi
  tpid=$!
  for _ in $(seq 1 15); do
    if grep -aq "AGENT LOG" tui.log 2>/dev/null; then tui=1; break; fi
    sleep 1
  done
  kill "$tpid" 2>/dev/null
  pkill -f -- "--connect http://127.0.0.1:${port}" 2>/dev/null
fi

kill "$pid" 2>/dev/null
echo "--- server.log"; head -c 20000 server.log; echo
if [ "$ok" != 1 ]; then echo "smoke: no /health after 120s"; exit 1; fi
echo "smoke: served /health"
# a built image once resolved SSL_* to the wrong OpenSSL and faulted on the
# boot-time provider handshake instead of failing on auth
if grep -q 'invalid memory reference' server.log; then echo "smoke: the TLS handshake faulted"; exit 1; fi
if [ "$tui" != 1 ]; then
  echo "--- tui.log"; head -c 4000 tui.log 2>/dev/null | tr -cd '[:print:]\n' ; echo
  echo "smoke: the TUI drew no frame"; exit 1
fi
echo "smoke: the TUI drew a frame"
