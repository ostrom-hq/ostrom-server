#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Measurement only (perf/gates-race-tokio): what a burst of requests leaves in the real binary, CIO engine, real
# sockets on localhost. Prints `CIO stage=... rss_kb=... threads=...` read from /proc of the server's process.
set -uo pipefail
APP_URL="$1"
run() { # $1 = name, $2.. = docker options
  local name="$1"; shift
  docker run -d --name "$name" --network host -e OSTROM_DATABASE_URL="$APP_URL" "$@" ostrom-server:ci >/dev/null
  for _ in $(seq 1 30); do curl -fsS localhost:8080/ready >/dev/null 2>&1 && break; sleep 1; done
  PID=$(docker inspect -f '{{.State.Pid}}' "$name")
}
field() { awk -v k="$1:" '$1==k {print $2}' "/proc/$PID/status" 2>/dev/null; }
cgroup() { docker stats --no-stream --format '{{.MemUsage}}' "$1" 2>/dev/null | cut -d/ -f1 | tr -d ' '; }
line() { echo "CIO stage=$1 rss_kb=$(field VmRSS) hwm_kb=$(field VmHWM) threads=$(field Threads) cgroup=$(cgroup "$2")"; }
burst() { # $1 = path, $2 = parallel, $3 = count
  seq "$3" | xargs -P "$2" -I{} curl -s -o /dev/null -w '%{http_code}\n' "localhost:8080$1" | sort | uniq -c | tr '\n' ' '
  echo
}
stage() { # $1 = stage name, $2 = container, $3 = path, $4 = parallel, $5 = count
  echo "codes $1: $(burst "$3" "$4" "$5")"
  line "$1+0s" "$2"; sleep 1; line "$1+1s" "$2"; sleep 2; line "$1+3s" "$2"; sleep 7; line "$1+10s" "$2"
  sleep 20; line "$1+30s" "$2"
}
run burst
line start burst
stage http-401 burst /api/v1/approvals 300 300
stage http-401-bounded burst /api/v1/approvals 16 300
stage health burst /health 300 300
stage http-401-x3 burst /api/v1/approvals 300 900
stage http-401-again burst /api/v1/approvals 300 300
docker stop -t 5 burst >/dev/null; docker rm burst >/dev/null
# The pod's limit: 256 MiB. Three bursts of 300, then whether the kernel killed it.
run limited --memory 256m --memory-swap 256m
line start limited
for i in 1 2 3; do stage "limited-$i" limited /api/v1/approvals 300 300; done
echo "CIO limited oom_killed=$(docker inspect -f '{{.State.OOMKilled}}' limited) running=$(docker inspect -f '{{.State.Running}}' limited)"
docker logs limited 2>&1 | tail -5
docker rm -f limited >/dev/null
exit 0
