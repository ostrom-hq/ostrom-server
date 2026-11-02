# The runner of cloud sessions

`ostrom-runner` is the process of a cloud session (module `runners` on the server side). A session runs the image
twice, as two containers that share only a socket directory:

- **The agent host** (`ostrom-runner agent-host`, UID 1000, the agent's container). It owns the workspace and runs
  each terminal the runner asks for on a PTY: `/bin/sh -c` cloning the repository and starting the agent, then a
  login shell once the agent exits. It never has the runner token.
- **The runner** (`ostrom-runner`, UID 1001). It holds the runner token, relays the terminal to the server's
  WebSocket `/api/v1/cloud/runner/terminal`, and heartbeats the execution on `/api/v1/cloud/runner/heartbeat`. It
  reaches the agent host through the Unix socket `/run/ostrom/agent.sock` (`OSTROM_AGENT_SOCKET`), opened to the
  group the two containers share (2000).

On Kubernetes the agent host is a native sidecar of the session pod (an init container with `restartPolicy: Always`,
**Kubernetes 1.29 or later**), so the pod ends when the runner ends. The token and the first message reach the
runner's container only, as variables the kubelet reads from the execution's Secret (`secretKeyRef`): they are in
no file, in no volume, and not in the pod's spec. The pod does not share its process namespace. On Docker, the
driver runs the same two containers.

## The agent cannot read the runner token (S2-49)

The agent and the runner never share a UID, a container, a process namespace or the token. CI proves it on the real
image (`ci/runner-isolation.sh`, step "Session image, the agent cannot read the runner token" of the `ci` job). It
runs one session as the Docker driver does, then checks four things:

- the runner started the agent's terminal through the host;
- the agent's container sees no process of another UID;
- nothing the agent can read holds the token: process environments, command lines, writable directories;
- the same search in the runner's container does find the token.

The image is built from `runner/Dockerfile`; the binary is native (Kotlin/Native, `linuxX64` and `linuxArm64`).

The agents' CLIs (Claude Code, Codex, opencode, Gemini CLI) are pinned in `runner/agents/package.json` and its
`package-lock.json`, installed by `npm ci`. To move a version: change it in `package.json`, run
`npm install --package-lock-only --ignore-scripts` in `runner/agents`, and commit both files. The build context is laid
out by `ci/session-image-context.sh`; the CI keeps an npm cache per lockfile (`ci/warm-agents-npm-cache.sh`).
