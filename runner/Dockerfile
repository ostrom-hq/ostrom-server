# The cloud session image: the runner, the agents' CLIs and the `ostrom` CLI, on Debian with Node.js for the agents.
# A session runs it three times (S2-49): `ostrom-runner agent-host` as UID 1001 runs the agent, with the workspace;
# `ostrom-runner` as UID 1000 holds the runner token and talks to the server; `ostrom-runner check-host` as UID 1001,
# in a container of its own with the workspace, runs the hooks' checks for the runner. All three are in group 1000
# (TeamCode's image contract). The agent and the runner share only the socket directory /run/ostrom; the runner and the
# check host only /run/ostrom-check, where the runner listens and the check host links to it. Contract with the server (modules/runners): /usr/local/bin/ostrom-runner, those UIDs and
# that group, /bin/sh, git and bash. Build context (ci/session-image-context.sh): bin/ostrom-runner and, when
# available, bin/ostrom; agents/ (package.json, package-lock.json); npm-cache/ (empty or a warm npm cache).
FROM node:22-bookworm-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends bash ca-certificates curl gh git less openssh-client procps python3 ripgrep \
 && rm -rf /var/lib/apt/lists/*

# The agents' CLIs, pinned in agents/package.json and its lockfile, installed by `npm ci`: the same versions on every
# build (a `latest` that changed under us broke the image, runs 41045488208). The build context may carry an npm cache
# in npm-cache/ (the CI keeps one per lockfile): mounted, never copied into a layer, so a warm cache reads nothing from
# the registry. npm retries a network error or a 5xx/429 only, with backoff; a missing version or a failing install
# script fails at once. On failure the full npm log is printed.
ENV NPM_CONFIG_FETCH_RETRIES=5 \
    NPM_CONFIG_FETCH_RETRY_MINTIMEOUT=10000 \
    NPM_CONFIG_FETCH_RETRY_MAXTIMEOUT=60000 \
    NPM_CONFIG_UPDATE_NOTIFIER=false
COPY agents/package.json agents/package-lock.json /opt/agents/
RUN --mount=type=bind,source=npm-cache,target=/tmp/npm-cache,rw \
    cd /opt/agents \
 && { npm ci --omit=dev --no-audit --no-fund --prefer-offline --cache /tmp/npm-cache --logs-dir /tmp/npm-logs \
      || { cat /tmp/npm-logs/*.log; exit 1; }; } \
 && rm -rf /tmp/npm-logs
ENV PATH=/opt/agents/node_modules/.bin:$PATH

COPY bin/ /opt/ostrom-bin/
RUN install -m 0755 /opt/ostrom-bin/* /usr/local/bin/ && rm -rf /opt/ostrom-bin \
 && useradd --uid 1001 --gid 1000 --no-create-home --home-dir /workspace/.home --shell /bin/bash agent \
 && mkdir -p /workspace /run/ostrom /run/ostrom-check && chown 1001:1000 /workspace \
 && chown 1001:1000 /run/ostrom /run/ostrom-check && chmod 2770 /run/ostrom /run/ostrom-check

# The image's `node` user (UID 1000, group 1000) is the runner's UID; `agent` (1001, group 1000) is the agent's and
# the image's default. The runner's container sets its own UID.
USER 1001:1000
WORKDIR /workspace
ENV HOME=/workspace/.home
ENTRYPOINT ["/usr/local/bin/ostrom-runner"]
