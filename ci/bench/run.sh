#!/usr/bin/env bash
# BENCH ONLY (never merged). Usage: ci/bench/run.sh <variant> <command to start the server...>
# Resets the database, migrates (native binary, same schema for every variant), seeds a QA account with the variant's
# own command, measures the start five times, then runs the k6 load while sampling the process every 5 s.
# The server runs on CPUs 0-1, k6 on CPUs 2-3.
set -euo pipefail
variant="$1"; shift
out="$RUNNER_TEMP/bench/$variant"; mkdir -p "$out"
export PGPASSWORD=ostrom
psql -h localhost -U ostrom -d ostrom_test -q -c "DROP DATABASE IF EXISTS ostrom WITH (FORCE)"
sed -n '/^CREATE DATABASE/,$p' ci/database-roles.sql | psql -h localhost -U ostrom -d ostrom_test -q -v ON_ERROR_STOP=1
OSTROM_DATABASE_MIGRATION_URL=postgresql://ostrom_owner:ostrom_owner@localhost:5432/ostrom dist/ostrom-server migrate
export OSTROM_DATABASE_URL=postgresql://ostrom_app:ostrom_app@localhost:5432/ostrom
export OSTROM_SIGNING_KEY_FILE="$RUNNER_TEMP/bench/instance-key.pem"
export OSTROM_SHUTDOWN_DELAY_SECONDS=0
export OSTROM_ALLOW_TEST_ACCOUNTS=true
export OSTROM_SIGNUP=open
token=$("$@" seed-test-account "bench-$variant@ostrom.test" | grep '"token"' | tail -1 | python3 -c 'import json,sys; print(json.loads(sys.stdin.read())["token"])')
echo "::add-mask::$token"

wait_ready() {
  for _ in $(seq 1 6000); do
    curl -fsS -o /dev/null localhost:8080/ready 2>/dev/null && return 0
    kill -0 "$1" 2>/dev/null || { echo "the server exited"; return 1; }
    sleep 0.01
  done
  echo "not ready after 60 s"; return 1
}
rss() { awk '/^VmRSS/ {print $2}' "/proc/$1/status"; }
threads() { awk '/^Threads/ {print $2}' "/proc/$1/status"; }
cpu() { awk '{print $14 + $15}' "/proc/$1/stat"; }

# Start: from exec to the first /ready 200, five times; the resident set at that moment.
: > "$out/startup.tsv"
for rep in 1 2 3 4 5; do
  t0=$(date +%s%N)
  taskset -c 0,1 "$@" > "$out/server-start-$rep.log" 2>&1 &
  pid=$!
  wait_ready "$pid" || { cat "$out/server-start-$rep.log"; exit 1; }
  t1=$(date +%s%N)
  printf '%s\t%s\t%s\n' "$rep" "$(( (t1 - t0) / 1000000 ))" "$(rss $pid)" >> "$out/startup.tsv"
  kill -TERM "$pid"; wait "$pid" || true
done
cat "$out/startup.tsv"

taskset -c 0,1 "$@" > "$out/server.log" 2>&1 &
pid=$!
wait_ready "$pid" || { cat "$out/server.log"; exit 1; }
echo "idle_rss_kb=$(rss $pid) threads=$(threads $pid)" | tee "$out/idle.txt"
( while kill -0 "$pid" 2>/dev/null; do
    printf '%s\t%s\t%s\t%s\n' "$(date +%s)" "$(rss $pid)" "$(threads $pid)" "$(cpu $pid)"; sleep 5
  done ) > "$out/samples.tsv" 2>/dev/null &
sampler=$!
date +%s > "$out/load-start.txt"
OSTROM_LOAD_TOKEN="$token" SUMMARY_FILE="$out/k6.json" taskset -c 2,3 k6 run --quiet ci/bench/load.js || true
date +%s > "$out/load-end.txt"
sleep 30
echo "after_rss_kb=$(rss $pid) threads=$(threads $pid)" | tee "$out/after.txt"
kill -TERM "$pid"; wait "$pid" || true
kill "$sampler" 2>/dev/null || true
grep -v '"level":"info"' "$out/server.log" | head -20 || true
