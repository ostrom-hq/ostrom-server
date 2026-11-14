#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Two real server processes on one PostgreSQL, as TeamCode's scripts/smoke-replicas.py (docs/replicas.md « Evidence »,
# parity TC-REP-7): both start at once on a migrated database; a token works on either; one replica killed (SIGKILL)
# leaves the other serving, and comes back; during a database outage /ready stays bounded and goes 503 once callers have
# waited 30 s on the pool (CRL-11), nobody is authenticated
# while both keep running, then both serve again without a restart; a token revoked on one replica is refused by the
# other. Only its own containers are stopped. Needs the image ostrom-server:ci and a database migrated by the image
# smoke step, reached as the serving role through OSTROM_SMOKE_DATABASE_URL
# (postgresql://user:password@host:port/database).
set -euo pipefail

url="${OSTROM_SMOKE_DATABASE_URL:?}"
# The replicas reach PostgreSQL through a TCP proxy of their own, so that the outage cuts them only.
proxied="$(sed -E 's#@([^/:]+):([0-9]+)/#@127.0.0.1:5499/#' <<<"$url")"
target="$(sed -E 's#.*@([^/:]+):([0-9]+)/.*#\1:\2#' <<<"$url")"
names=(ostrom-replica-a ostrom-replica-b)
proxy_pid=""
callers=()

cleanup() {
  status=$?
  if [ "$status" -ne 0 ]; then
    for name in ostrom-replica-a ostrom-replica-b; do echo "--- $name"; docker logs "$name" 2>&1 | tail -40 || true; done
  fi
  docker rm -f "${names[@]}" >/dev/null 2>&1 || true
  [ -z "$proxy_pid" ] || kill "$proxy_pid" 2>/dev/null || true
  # The callers of step 4 (this script's own subshells), if it stopped before they ended.
  [ "${#callers[@]}" -eq 0 ] || kill "${callers[@]}" 2>/dev/null || true
}
trap cleanup EXIT

# A TCP proxy of this script's own (its PID kept, the only process it stops): every connection through it ends with it.
proxy() {
  python3 - "$target" <<'PY' &
import socket, sys, threading
host, port = sys.argv[1].rsplit(":", 1)
server = socket.socket()
server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server.bind(("127.0.0.1", 5499))
server.listen(64)
def pipe(a, b):
    try:
        while data := a.recv(65536):
            b.sendall(data)
    except OSError:
        pass
    finally:
        a.close()
        b.close()
while True:
    client, _ = server.accept()
    upstream = socket.create_connection((host, int(port)))
    threading.Thread(target=pipe, args=(client, upstream), daemon=True).start()
    threading.Thread(target=pipe, args=(upstream, client), daemon=True).start()
PY
  proxy_pid=$!
  sleep 1
}

# Starts replica $1 on port $2 (detached).
replica() {
  docker run -d --name "$1" --network host -e OSTROM_PORT="$2" -e OSTROM_DATABASE_URL="$proxied" \
    -e OSTROM_SHUTDOWN_DELAY_SECONDS=1 ostrom-server:ci >/dev/null
}

# Waits up to $2 seconds for `curl` of $1 to answer HTTP $3.
await_status() {
  local path="$1" seconds="$2" want="$3" got=""
  for _ in $(seq 1 "$seconds"); do
    got="$(curl -s --max-time 10 -o /dev/null -w '%{http_code}' "$path" || true)"
    [ "$got" = "$want" ] && return 0
    sleep 1
  done
  echo "$path answered $got, not $want, within ${seconds}s"; exit 1
}

# The HTTP status of GET /api/v1/auth/me on port $1 with the QA token.
me() {
  curl -s --max-time 10 -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $token" "localhost:$1/api/v1/auth/me" \
    || true
}

proxy
account="$(docker run --rm --network host -e OSTROM_DATABASE_URL="$proxied" -e OSTROM_ALLOW_TEST_ACCOUNTS=true \
  ostrom-server:ci seed-test-account replicas@ostrom.test)"
token="$(sed -nE 's/.*"token":"([^"]+)".*/\1/p' <<<"$account" | tail -1)"
[ -n "$token" ] || { echo "no token from seed-test-account"; exit 1; }

echo "1. Both replicas start at once on the same database."
replica ostrom-replica-a 8082 &
a=$!
replica ostrom-replica-b 8083 &
b=$!
# Their two `docker run` only: the proxy runs in the background too.
wait "$a" "$b"
await_status localhost:8082/ready 30 200
await_status localhost:8083/ready 30 200

echo "2. A token works on either replica: the sessions are in PostgreSQL, not in a process."
[ "$(me 8082)" = 200 ] && [ "$(me 8083)" = 200 ] || { echo "the token is refused: $(me 8082) $(me 8083)"; exit 1; }

echo "3. Replica A is killed (SIGKILL): B keeps serving; A comes back."
docker kill -s KILL ostrom-replica-a >/dev/null
[ "$(me 8083)" = 200 ] || { echo "B stopped serving once A was killed"; exit 1; }
docker start ostrom-replica-a >/dev/null
await_status localhost:8082/ready 30 200
[ "$(me 8082)" = 200 ] || { echo "A came back without the session"; exit 1; }

echo "4. The database goes away: /ready stays bounded, then 503 once callers have waited 30 s on the pool; nobody is"
echo "   authenticated, both keep running, then serve again."
kill "$proxy_pid"
wait "$proxy_pid" 2>/dev/null || true
proxy_pid=""
outage="$(date +%s)"
# CRL-11: /ready reads the JDBC pool's state, never a query, so that a short database stall does not drain every
# replica at once: it goes 503 only once callers have waited for a connection 30 s without a break (PoolHealthCheck).
# Identity authenticates on that pool, so callers that keep asking wait on it (each up to the 5 s connection timeout).
for port in 8082 8083; do
  for _ in 1 2 3 4 5 6; do
    ( while [ $(($(date +%s) - outage)) -lt 55 ]; do me "$port" >/dev/null; done ) &
    callers+=($!)
  done
done
declare -A unready=()
while [ $(($(date +%s) - outage)) -lt 50 ] && [ "${#unready[@]}" -lt 2 ]; do
  for port in 8082 8083; do
    [ -z "${unready[$port]:-}" ] || continue
    ready="$(curl -s --max-time 2 -o /dev/null -w '%{http_code}' "localhost:$port/ready" || true)"
    elapsed=$(($(date +%s) - outage))
    case "$ready" in
      200) ;;
      503)
        # Margin for the callers' first wait to start: 503 before 25 s would drain on a short stall.
        [ "$elapsed" -ge 25 ] || { echo "replica $port: /ready 503 after ${elapsed}s only, before 30 s of waits"; exit 1; }
        unready[$port]="$elapsed"
        echo "   replica $port: /ready 503 after ${elapsed}s of callers waiting on the pool" ;;
      *) echo "replica $port: /ready did not answer within 2 s without its database ($ready)"; exit 1 ;;
    esac
  done
  sleep 1
done
for port in 8082 8083; do
  [ -n "${unready[$port]:-}" ] || { echo "replica $port: /ready still 200 after 50 s of callers waiting"; exit 1; }
  [ "$(me "$port")" != 200 ] || { echo "replica $port authenticated without its database"; exit 1; }
done
wait "${callers[@]}" 2>/dev/null || true
for name in ostrom-replica-a ostrom-replica-b; do
  running="$(docker inspect -f '{{.State.Running}}' "$name")"
  [ "$running" = true ] || { echo "$name exited without its database"; exit 1; }
done
proxy
await_status localhost:8082/ready 60 200
await_status localhost:8083/ready 60 200
for port in 8082 8083; do
  status=""
  for _ in $(seq 1 30); do
    status="$(me "$port")"
    [ "$status" = 200 ] && break
    sleep 1
  done
  [ "$status" = 200 ] || { echo "replica $port did not serve again once its database came back ($status)"; exit 1; }
done

echo "5. A token revoked on B is refused by A."
revoked="$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $token" \
  localhost:8083/api/v1/auth/logout)"
[ "$revoked" = 204 ] || { echo "logout on B answered $revoked"; exit 1; }
[ "$(me 8082)" = 401 ] || { echo "A still takes a token B revoked: $(me 8082)"; exit 1; }
[ "$(me 8083)" = 401 ] || { echo "B still takes the token it revoked"; exit 1; }

echo "Two replicas on one database: every step held."
