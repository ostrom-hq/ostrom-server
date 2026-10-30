#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# S2-49: in a cloud session, the agent cannot read the runner token. Builds the session image (runner/Dockerfile)
# around the runner binary given as $1, and runs one session as the Docker runtime driver does: the agent host as
# UID 1000 with the workspace and the socket, the runner as UID 1001 with the token and the socket only. Then:
#   - the runner started the agent's terminal through the host (a bash runs in the agent's container);
#   - the agent's container sees no process of another UID;
#   - nothing the agent's container can read holds the token (every process's environment and command line, and
#     every writable directory);
#   - the same search, run in the runner's container, finds the token: the search itself works.
# Linux x64 hosts with Docker only (CI).
set -euo pipefail

binary="$1"
image=ostrom-runner:isolation
context=$(mktemp -d)
mkdir -p "$context/bin"
cp "$binary" "$context/bin/ostrom-runner"
cp runner/Dockerfile "$context/Dockerfile"
docker build -q -t "$image" "$context" > /dev/null

token="ostrom_runner_$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
session=$(cat /proc/sys/kernel/random/uuid)
cleanup() {
  docker rm -f iso-runner iso-agent > /dev/null 2>&1 || true
  docker volume rm iso-workspace iso-socket > /dev/null 2>&1 || true
}
trap cleanup EXIT
fail() {
  echo "$1" >&2
  docker logs iso-agent || true
  docker logs iso-runner || true
  exit 1
}

docker volume create iso-workspace > /dev/null
docker volume create iso-socket > /dev/null
hardening=(--read-only --tmpfs "/tmp:rw,nosuid,size=64m" --cap-drop ALL --security-opt no-new-privileges)
docker run -d --name iso-agent --user 1000:1000 "${hardening[@]}" \
  -v iso-workspace:/workspace -v iso-socket:/run/ostrom -w /workspace \
  -e OSTROM_AGENT_SOCKET=/run/ostrom/agent.sock -e HOME=/workspace/.home \
  --entrypoint /usr/local/bin/ostrom-runner "$image" agent-host > /dev/null
docker run -d --name iso-runner --user 1001:1001 --group-add 2000 "${hardening[@]}" \
  -v iso-socket:/run/ostrom \
  -e OSTROM_RUNNER_TOKEN="$token" -e OSTROM_SERVER_URL=http://127.0.0.1:9 -e OSTROM_SESSION_ID="$session" \
  -e OSTROM_EPOCH=1 -e OSTROM_AGENT=shell -e OSTROM_AGENT_COMMAND=bash \
  -e OSTROM_AGENT_SOCKET=/run/ostrom/agent.sock -e HOME=/workspace/.home -e TERM=xterm-256color \
  --entrypoint /usr/local/bin/ostrom-runner "$image" > /dev/null

for _ in $(seq 1 30); do
  docker exec iso-agent pgrep -x bash > /dev/null 2>&1 && break
  sleep 1
done
docker exec iso-agent pgrep -x bash > /dev/null || fail "The agent's terminal did not start through the agent host."
test "$(docker exec iso-agent id -u)" = 1000 || fail "The agent does not run as UID 1000."
test "$(docker exec iso-runner id -u)" = 1001 || fail "The runner does not run as UID 1001."
others=$(docker exec iso-agent ps -eo uid= | awk '$1 != 1000')
test -z "$others" || fail "The agent's container sees processes of another UID: $others"

# What a container's UID can read: every process's environment and command line, and the writable directories.
# Dumped to a file first: `grep -q` stops reading at its first match, and under pipefail the writer's SIGPIPE would
# turn a match into a failure of the pipeline.
readable() {
  docker exec "$1" sh -c 'cat /proc/[0-9]*/environ /proc/[0-9]*/cmdline 2> /dev/null;
    tar -cf - /workspace /run/ostrom /tmp /etc /var /home /root 2> /dev/null; true' > "$context/$1.dump"
}
readable iso-agent
readable iso-runner
if grep -qaF "$token" "$context/iso-agent.dump"; then fail "The agent's container can read the runner token."; fi
grep -qaF "$token" "$context/iso-runner.dump" || fail "The search did not find the token in the runner's container."
echo "S2-49: the agent (UID 1000) cannot read the runner token; the runner (UID 1001) holds it."
