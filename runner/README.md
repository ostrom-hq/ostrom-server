# The runner of cloud sessions

`ostrom-runner` is the process of a cloud session (module `runners` on the server side). A session runs the image
three times, as three containers; each pair shares at most one socket directory:

- **The agent host** (`ostrom-runner agent-host`, UID 1000, the agent's container). It owns the workspace and runs
  each terminal the runner asks for on a PTY: `/bin/sh -c` cloning the repository and starting the agent, then a
  login shell once the agent exits. It never has the runner token.
- **The runner** (`ostrom-runner`, UID 1001). It holds the runner token, relays the terminal to the server's
  WebSocket `/api/v1/cloud/runner/terminal`, and heartbeats the execution on `/api/v1/cloud/runner/heartbeat`. It
  reaches the agent host through the Unix socket `/run/ostrom/agent.sock` (`OSTROM_AGENT_SOCKET`), opened to the
  group the containers share (2000). For every agent but the plain shell, it opens the agent's launch on
  `/api/v1/cloud/runner/launch` before starting the terminal, and gives the agent `OSTROM_LAUNCH_ID`,
  `OSTROM_AGENT_TOKEN`, `OSTROM_URL` and `OSTROM_RUNNER_CHECKS`. When the server refuses the launch (the
  organization's policy, a limit) or never answers it, the agent does not start: the terminal says why and leaves a
  shell (`OSTROM_LAUNCH_REFUSED`).
- **The check host** (`ostrom-runner check-host`, UID 1000, a container of its own). It mounts the workspace and runs
  the hooks' checks the runner sends it, without a terminal (`/bin/sh -c`, stdin from `/dev/null`, stdout and stderr
  on one pipe), and reports each exit code. The runner listens on `/run/ostrom-check/check.sock`
  (`OSTROM_CHECK_HOST_SOCKET`), on a volume only the runner's container also mounts, and the check host links to it
  as it starts: the runner accepts that one link, then for each check a connection that opens with the nonce it sent
  over the link. The check host is the pid 1 of its container and not dumpable; after each check it kills every other
  process of its uid and removes the check's home (`HOME` and `TMPDIR`, new for each check), and a script inherits none
  of its descriptors. Checks run one at a time. Before each, the runner binds its socket again if a script replaced it.
  It never has the runner token.

On Kubernetes the agent host is a native sidecar of the session pod (an init container with `restartPolicy: Always`,
**Kubernetes 1.29 or later**), so the pod ends when the runner ends. The token and the first message reach the
runner's container only, as variables the kubelet reads from the execution's Secret (`secretKeyRef`): they are in
no file, in no volume, and not in the pod's spec. The check host is a second native sidecar. The pod does not share
its process namespace. On Docker, the driver runs the same three containers.

## A registered runner on a machine (`ostrom-runner machine`)

An owner or an admin registers a machine of the organization with `POST /api/v1/runners`; the answer shows the
runner's key once. On that machine, `ostrom-runner machine` signs of life with it on
`POST /api/v1/runners/{id}/heartbeat`, at once and then every 20 s, so `GET /api/v1/runners` shows it `online`
(offline 90 s after its last heartbeat):

```sh
docker run -d --name ostrom-machine \
  -e OSTROM_SERVER_URL=https://ostrom.example.com \
  -e OSTROM_RUNNER_ID=<id> -e OSTROM_RUNNER_KEY_FILE=/run/ostrom/key -v /etc/ostrom/key:/run/ostrom/key:ro \
  ghcr.io/ostrom-hq/ostrom-runner machine
```

| Variable | |
|---|---|
| `OSTROM_SERVER_URL` | the server, `http(s)://` |
| `OSTROM_RUNNER_ID` | the registered runner |
| `OSTROM_RUNNER_KEY` or `OSTROM_RUNNER_KEY_FILE` | its key; the file is better, the key is never logged |
| `OSTROM_RUNNER_INTERVAL_SECONDS` | between heartbeats, 1 to 30 (20 by default): a lost heartbeat stays inside the 90 s |

A heartbeat that fails (server unreachable, 5xx) is logged with its cause and retried at the next interval. When the
server refuses the key three times in a row (401: the runner was removed, or its key replaced; a single 401 may come
from a proxy), the process ends with code 3. A bad configuration, an unreadable key file included, ends it with code 2,
a stop (SIGTERM) with 0.

### Cloud sessions on a registered runner

With a Docker Engine (`OSTROM_DOCKER_API_URL`) and a server whose sessions run on registered runners
(`OSTROM_RUNNERS_DRIVER=registered`), the machine runs the organization's cloud sessions. Each heartbeat reports what
it holds and gets back what it must hold:

- an execution handed over comes with its plan, once: the volumes and the three containers (agent host, check host,
  runner) that the server's Docker driver would create, with the execution's runner token. The machine checks the plan
  before creating anything: the session's own named volumes only, never a host path, a privilege, a capability, a host
  network or another image than `OSTROM_RUNNER_SESSION_IMAGE` when set, and as a non-root user. A refused plan is
  logged and the execution reported missing, so the server moves the session on;
- an execution being created (an image pull may take minutes) is reported `pending`, without holding up the
  heartbeats; its runner's container then makes it `running`, and its end `ended`;
- every object the machine creates carries the label `ostrom.run/runner=<id>`. The machine stops and deletes its own
  containers and volumes the server no longer lists (a stopped or moved execution, a deleted workspace) and never
  touches anything else on the engine.

When a machine stops heartbeating, the server gives its running sessions to another online runner of the
organization after 90 s, as a new execution with a fresh workspace (the old one stays on the lost machine). A paused
session resumes on the machine that holds its workspace while that machine is online. A machine whose engine cannot
be read heartbeats without a report, and is given nothing that time.

```sh
docker run -d --name ostrom-machine --network host \
  -e OSTROM_SERVER_URL=https://ostrom.example.com \
  -e OSTROM_RUNNER_ID=<id> -e OSTROM_RUNNER_KEY_FILE=/run/ostrom/key -v /etc/ostrom/key:/run/ostrom/key:ro \
  -e OSTROM_DOCKER_API_URL=http://127.0.0.1:2375 \
  ghcr.io/ostrom-hq/ostrom-runner machine
```

| Variable | |
|---|---|
| `OSTROM_DOCKER_API_URL` | the Docker Engine API, `http(s)://`; for the local socket, a socket proxy on the loopback that allows containers, volumes and images |
| `OSTROM_DOCKER_NETWORK` | the network the sessions join (the engine's default when unset); their containers reach the server through `host.docker.internal` |
| `OSTROM_RUNNER_SESSION_IMAGE` | the only image a session may run; the server's choice when unset |

## Checks run by the runner (`isolated_runner`)

In a cloud session, the agent's hook does not run a package check itself: it asks the runner on
`/run/ostrom/checks.sock` (`OSTROM_RUNNER_CHECKS`), one JSON line per connection:

```json
{"sessionId": "…", "digest": "sha256:…", "nodeId": "…", "scriptId": "lint", "script": "npm run lint\n",
 "directory": "/workspace/api", "timeoutMs": 600000, "head": "c0ffee…"}
```

The runner refuses a script over 64 KiB, a directory outside the workspace and a time limit outside 1 ms to 1 h. It
computes the SHA-256 of the script text itself, runs the script on the check host with a clean environment (`PATH`,
`LANG`, `HOME=/tmp`, `CI=1`, `OSTROM_CHECK`), stops it at its time limit, and posts the run to
`/api/v1/cloud/runner/checks` with its runner token. The server records it with the attestation `isolated_runner`,
and as tampered when the SHA-256 is not the package's. The hook gets one JSON line back:

```json
{"started": true, "exit": 0, "outcome": "passed", "scriptSha": "…", "outputTail": "…", "reported": true}
```

This is the exchange the hook of `ostrom-cli` speaks (cli#68): it reads `started`, `exit` (an integer, or `null` when
the script ran past its limit) and `outputTail`, and ignores the rest. `started: false` means the check was not run
(`outputTail` says why): the hook refuses the action and never runs the check itself. The hook waits for the script's
time limit and 5 s more, so the runner gives up on a report the server has not taken within 3 s (`reported: false`).
Two checks run at a time at most.

**What this proves, and what it does not: Ostrom launched the script and read its exit code, not that the agent left
the workspace untouched.** The check runs on the agent's workspace, which the agent could have prepared.

The check host runs under the agent's UID so that what a check writes in the workspace stays the agent's to change;
what keeps the agent out of the check is the container: the agent's container mounts neither the check host's
socket nor its temporary directory, and sees none of its processes.

## The agent cannot read the runner token (S2-49)

The agent and the runner never share a UID, a container, a process namespace or the token. CI proves it on the real
image (`ci/runner-isolation.sh`, step "Session image, the agent cannot read the runner token" of the `ci` job). It
runs one session as the Docker driver does, then checks:

- the runner started the agent's terminal through the host;
- the agent's container sees no process of another UID;
- nothing the agent can read holds the token: process environments, command lines, writable directories;
- the same search in the runner's container does find the token;
- a check asked from the agent's container runs on the check host, the runner returns its exit code and the SHA-256
  of the script, the check's environment and the check host's container hold no token, and the agent's container
  sees neither the check host's socket nor the running check.

The image is built from `runner/Dockerfile`; the binary is native (Kotlin/Native, `linuxX64` and `linuxArm64`).

The agents' CLIs (Claude Code, Codex, opencode, Gemini CLI) are pinned in `runner/agents/package.json` and its
`package-lock.json`, installed by `npm ci`. To move a version: change it in `package.json`, run
`npm install --package-lock-only --ignore-scripts` in `runner/agents`, and commit both files. The build context is laid
out by `ci/session-image-context.sh`; the CI keeps an npm cache per lockfile (`ci/warm-agents-npm-cache.sh`).

Every Monday the Agents pins workflow (`.github/workflows/agents-pins.yml`, also run by hand from the Actions tab)
moves all four to their latest versions (`ci/update-agents-pins.sh`) and opens a pull request; it is never merged
automatically and goes through the full `ci`, the S2-49 isolation test included. To pin one version by hand, keep
another back for instance, set the exact version in `runner/agents/package.json` (never a range), run
`npm install --package-lock-only --ignore-scripts` in `runner/agents` and open a pull request with both files.
