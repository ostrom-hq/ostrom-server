# The cloud session image: the runner (pid 1 of the session pod), the agents' CLIs and the `ostrom` CLI, on Debian
# with Node.js for the agents. Contract with the server (modules/runners): /usr/local/bin/ostrom-runner, UID 1000,
# /bin/sh, git and bash. Build context: a directory holding bin/ostrom-runner and, when available, bin/ostrom.
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
 && mkdir -p /workspace && chown 1000:1000 /workspace

# The image's `node` user is UID 1000.
USER 1000:1000
WORKDIR /workspace
ENV HOME=/workspace/.home
ENTRYPOINT ["/usr/local/bin/ostrom-runner"]
