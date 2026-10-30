#!/usr/bin/env bash
# Proves the server's telemetry reaches a real OpenTelemetry collector (BRIEF, rule TESTS: integration with a collector
# in CI): one traced request, then its span, the request metrics and a log line are read back from the collector's
# files. Needs the image ostrom-server:ci and the CI job's PostgreSQL service (ostrom/ostrom on localhost:5432).
set -euo pipefail

out="$(mktemp -d)"
chmod 777 "$out"
cleanup() {
  docker rm -f ostrom-otel otel-collector >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker run -d --name otel-collector --network host \
  -v "$PWD/ci/otel-collector.yaml:/etc/otelcol-contrib/config.yaml:ro" -v "$out:/out" \
  otel/opentelemetry-collector-contrib:0.120.0 >/dev/null

docker run -d --name ostrom-otel --network host \
  -e OSTROM_PORT=8081 \
  -e OSTROM_DATABASE_URL=postgresql://ostrom:ostrom@localhost:5432/ostrom_test \
  -e OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 \
  -e OTEL_RESOURCE_ATTRIBUTES=deployment.environment.name=ci \
  ostrom-server:ci >/dev/null
for _ in $(seq 1 30); do curl -fsS localhost:8081/health >/dev/null 2>&1 && break; sleep 1; done

trace_id="4bf92f3577b34da6a3ce929d0e0e4736"
headers="$(curl -fsS -D - -o /dev/null -H "traceparent: 00-${trace_id}-00f067aa0ba902b7-01" localhost:8081/v1/instance)"
echo "$headers" | grep -i "^traceresponse: 00-${trace_id}-" || { echo "no traceresponse header"; exit 1; }

# Spans and logs leave in batches every 5 s, metrics every 30 s: wait for each file to show what it should.
wait_for() {
  local file="$1" pattern="$2"
  for _ in $(seq 1 45); do
    grep -q "$pattern" "$out/$file" 2>/dev/null && return 0
    sleep 1
  done
  echo "nothing matching $pattern in $file"; ls -l "$out"; docker logs otel-collector | tail -20; exit 1
}
wait_for traces.json "\"traceId\":\"${trace_id}\""
wait_for traces.json '"name":"GET /v1/instance"'
wait_for logs.json '"service.name"'
wait_for metrics.json '"name":"http.server.request.duration"'
wait_for metrics.json '"name":"process.cpu.time"'
grep -q '"key":"service.namespace","value":{"stringValue":"ostrom"}' "$out/traces.json"
grep -q '"key":"deployment.environment.name","value":{"stringValue":"ci"}' "$out/traces.json"
if grep -q '/health' "$out/traces.json"; then echo "/health must not be traced"; exit 1; fi
echo "Traces, metrics and logs reached the collector."
