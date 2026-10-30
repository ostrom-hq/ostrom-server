# The cloud session image: the runner, the agents' CLIs and the `ostrom` CLI, on Debian with Node.js for the agents.
# A session runs it twice (S2-49): `ostrom-runner agent-host` as UID 1000 runs the agent, with the workspace;
# `ostrom-runner` as UID 1001 holds the runner token and talks to the server. They share only the socket directory
# /run/ostrom (group 2000). Contract with the server (modules/runners): /usr/local/bin/ostrom-runner, those UIDs and
# that group, /bin/sh, git and bash. Build context: a directory holding bin/ostrom-runner and, when available,
# bin/ostrom.
FROM node:22-bookworm-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends bash ca-certificates curl git less openssh-client procps python3 ripgrep \
 && rm -rf /var/lib/apt/lists/*

# Agents installed globally, pinned by the build arguments (empty: the latest at build time).
ARG CLAUDE_CODE_VERSION=latest
ARG CODEX_VERSION=latest
ARG OPENCODE_VERSION=latest
ARG GEMINI_CLI_VERSION=latest
RUN npm install -g \
      "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}" \
      "@openai/codex@${CODEX_VERSION}" \
      "opencode-ai@${OPENCODE_VERSION}" \
      "@google/gemini-cli@${GEMINI_CLI_VERSION}" \
 && npm cache clean --force

COPY bin/ /opt/ostrom-bin/
RUN install -m 0755 /opt/ostrom-bin/* /usr/local/bin/ && rm -rf /opt/ostrom-bin \
 && mkdir -p /workspace /run/ostrom && chown 1000:1000 /workspace \
 && chown 1000:2000 /run/ostrom && chmod 2770 /run/ostrom

# The image's `node` user is UID 1000: the agent's. The runner's container sets its own UID.
USER 1000:1000
WORKDIR /workspace
ENV HOME=/workspace/.home
ENTRYPOINT ["/usr/local/bin/ostrom-runner"]
