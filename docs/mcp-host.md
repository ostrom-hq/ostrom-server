# MCP stdio host (`mcp-host`)

`mcp-host` runs an organization's MCP servers published as **stdio** packages (npm, PyPI) for the MCP relay
(`modules/mcp-relay`). Agents only ever talk to the relay. The relay keeps every authorization decision, every tool
policy and the journal. The host sits behind it: it starts one process per key, speaks stdio JSON-RPC with that
process, and answers the relay over an internal HTTP protocol. Agents never reach it, and nothing runs on a member's
workstation or in a session pod.

The host is the Gradle module `:mcp-host`. Its image is `ghcr.io/ostrom-hq/ostrom-mcp-host`. It is a port of
TeamCode's `mcp` module (TeamCode `docs/mcp.md`) and speaks the same host protocol, version 1.

## Status

| Part | State |
| --- | --- |
| Host: supervisor, stdio transport, host protocol v1, limits, scrubbing, drain on SIGTERM | Done (`:mcp-host`) |
| Image (amd64, arm64) and its contract test with real npm and PyPI servers | Done (`mcp-host-image.yml`) |
| Relay → host operations (`HostMcpUpstream`, `POST /v1/ops`; `OSTROM_MCP_HOST_URL`, `OSTROM_MCP_HOST_TOKEN`) | Done (`mcp-relay`) |
| Warm-up from the member's server list, the `retain` sweep, `/v1/stop`, the recorded state of each process | Next |
| Helm component (Deployment, Service, NetworkPolicy, generated token Secret) | Next (ostrom-helm) |

## Architecture

- **One owner.** The server runs several stateless replicas, but a stdio server is a stateful process: it gets one
  `initialize` and keeps one session on stdin and stdout. So one host (a Deployment with one replica) owns every
  process. Any server replica reaches it through its Service with the host token.
- **Self-contained requests.** Each request carries everything the host needs: the key, the definition, the
  secrets and the operation. The host keeps nothing on disk beyond per-key caches. A restart only loses those
  caches, and the next operation starts the process again.
- **One process per key.** The relay computes the key: SHA-256 of `org:server:version`, plus
  `:member:assignedAt` for a per-member server, plus the secrets. A secret rotation therefore yields a new key and
  a new process.
- **Multiplexing.** `initialize` runs once per process, and concurrent operations share its stdin, told apart by
  JSON-RPC id.
- **No privileged access.** The host has no database, no encryption key and no Kubernetes token, and depends on
  no other module.
- **Code layout.** The logic is in `commonMain`, behind small platform interfaces (`ProcessSpawner`,
  `ChildProcess`, `ProcessTable`, `HostFiles`, `EventSink`). `jvmMain` implements them with `ProcessBuilder`,
  `ProcessHandle` and `/proc`, holds the entry point, and ships as a JVM application, the direction the whole
  server takes.

## Host protocol v1

The protocol is internal HTTP on port 8090, released with the server at the same version.

- **Headers.** Every `/v1/…` request carries `Authorization: Bearer <OSTROM_MCP_HOST_TOKEN>`, compared in
  constant time; a wrong or missing token gets 401 `unauthorized`. It also carries `TeamCode-Host-Protocol: 1`.
  Another value, or no header, gets 400 `unsupported_protocol`, with a message asking to deploy the server and the
  host at the same version.
- **Bodies** are JSON, at most 2 MiB (413 above that).

| Method and path | Body | Answer |
| --- | --- | --- |
| `POST /v1/ops` | `{key, spec, op: "list" \| "call", tool?, arguments?, deadlineMs, maxResponseBytes}` | 200 `{result \| error, cold, state}` |
| `POST /v1/warm` | `{key, spec}` | 202 `{key, state}`. Starts the process without waiting |
| `POST /v1/retain` | `{keys: […]}` | 200 `{stopped}`. Stops every unlisted process and removes its directory; one first seen less than a minute ago is spared |
| `POST /v1/stop` | `{key}` | 200 `{stopped}`. The administrator's "Restart": stops the process and lifts a backoff |
| `GET /v1/status` | | 200. The version, the launchers, and per key: state, pid, counters, last failure, cold start time, memory, last 64 KiB of scrubbed stderr |
| `GET /health` | | 200 once the host serves (no token) |
| `GET /ready` | | 200 when not draining and the launcher commands are present, 503 otherwise (no token) |

**The definition.** `spec` is `{launcher, package, args, env, secrets, declaredAt}`:

- `launcher` is `npm` or `pypi`, and `package` is the exactly pinned package.
- `args` are the arguments that follow the package.
- `env` holds the plain variables and `secrets` the secret ones.
- `declaredAt` is when the server was declared, in epoch milliseconds.

Secrets travel only in this request and live only in the process environment. At launch, `${NAME}` in an argument
is replaced by the secret `NAME`.

**The answer.**

- `result` is `{"tools": […]}` for `list`. The host reads every page, at most 16 pages and 256 tools, and caches
  the list until the server sends `notifications/tools/list_changed`. For `call`, `result` is the server's
  `CallToolResult`.
- Every secret value of the process is scrubbed from both, JSON keys included.
- `cold` says whether the operation waited for a start.
- `state` is `idle`, `starting`, `ready`, `failed` or `backoff`.

| Code | Meaning |
| --- | --- |
| `start_failed` | The process did not start: launcher missing, package not found (a version newer than the declaration date, say), early exit, no `tools` capability, unsupported protocol version, or no `initialize` answer within 45 s |
| `crashed` | The process ended during the operation (exit, memory limit, stdin stall, invalid output, revocation) |
| `timeout` | No answer within `deadlineMs`. The host sends `notifications/cancelled` and keeps the process; a start goes on for the next operation |
| `too_large` | The answer exceeds `maxResponseBytes`, or its line exceeds 4 MiB |
| `busy` | `maxInFlightPerProcess` requests were already running for the whole deadline |
| `backoff` | More than 3 failures in 10 minutes. No start for 10 minutes, or until `/v1/stop` or a new definition |
| `capacity` | `maxProcesses` processes run and none is idle |
| `protocol` | The process answered `tools/list` or `tools/call` with something that is not MCP |
| `tool_error` | The process answered `tools/call` with a JSON-RPC error (scrubbed message) |
| `invalid_spec` | The definition breaks a launch rule (below) |
| `bad_request` (400) | Malformed request, a key that is not 64 lowercase hexadecimal characters, an unknown `op`, an out-of-range deadline or size |
| `shutting_down` (503) | The host is stopping |

A closed HTTP connection during `/v1/ops` counts as a cancellation, and the process receives
`notifications/cancelled`. The host never replays a `tools/call`.

## Launching a process

No shell is involved. The host builds the argv itself and starts it under `tini` (PID 1 of the image, which reaps
orphans) and `prlimit`.

| Launcher | Command |
| --- | --- |
| `npm` | `prlimit --nproc=1024 --nofile=1024 -- npx --yes -- @scope/name@1.2.3 args…` |
| `pypi` | `prlimit --nproc=1024 --nofile=1024 -- uvx --exclude-newer <declaredAt> [--default-index <index>] name==1.2.3 args…` |

**The environment** is built from scratch. It holds:

- `PATH`, `HOME`, `TMPDIR` and `LANG`;
- the launcher's own settings;
- the definition's variables and secrets.

Nothing of the host's own environment passes. Each key has its own directory `/var/mcp/<key>/`, with `home`, `work`
(the working directory), `tmp`, and the npm or uv caches, tools and Python, so a package cannot poison another key's
cache.

**Rules**, refused with `invalid_spec`:

- The launcher is one of `OSTROM_MCP_HOST_LAUNCHERS`.
- The version is exact. For npm, `name@1.2.3` or `@scope/name@1.2.3`; for PyPI, `name==1.2.3`, extras allowed.
  Ranges, tags, paths and URLs are refused.
- At most 32 arguments, each at most 512 characters, without control characters. `${NAME}` must name a secret.
- Variable names use `A-Z 0-9 _` and avoid the reserved names and prefixes:
  - names such as `PATH`, `HOME`, `LANG`, runtime options, proxies, certificate bundles, `GLIBC_TUNABLES`;
  - prefixes such as `LD_`, `OSTROM_`, `NPM_CONFIG_`, `UV_`, `PIP_`, `NODE_`, `PYTHON`, `COREPACK_`, `SSL`, `CURL_`.

  The full lists are in `Launchers.kt`.

  So a definition cannot move the registry, the cut-off date, the install scripts, the TLS checks or the loader.
  Values contain no NUL.
- At most 16 secrets, 16 KiB in total, each at least 8 characters long, so that scrubbing it never mangles ordinary
  output.

**Supply chain.**

- For npm, `npm_config_before=<declaredAt>` resolves the package and all its transitive dependencies as they were
  on the declaration day, so a version published later is never taken. `npm_config_ignore_scripts=true` disables
  install scripts.
- For PyPI, `uvx --exclude-newer <declaredAt>` plays the same role.
- `OSTROM_MCP_HOST_NPM_REGISTRY` and `OSTROM_MCP_HOST_PYPI_INDEX` point at a mirror.

## The stdio transport

| Subject | Rule |
| --- | --- |
| Framing | One UTF-8 JSON-RPC message per line in both directions |
| Non-JSON stdout | Ignored and counted (many servers print a banner). More than 100 such lines in a minute restart the process |
| stderr | Kept in a 64 KiB in-memory buffer, scrubbed when read, never logged or stored |
| `initialize` | Revisions 2025-11-25, 2025-06-18 and 2025-03-26, then `notifications/initialized`. A server without the `tools` capability fails to start |
| Server notifications | `tools/list_changed` clears the cached list, `message` and `progress` go to the stderr buffer, others are ignored |
| Server requests | `ping` is answered; any other request gets `-32601` at once |
| Size | A line over 4 MiB is dropped unparsed. The call whose `id` sits at the line's start or end fails at once as `too_large`. Three such lines in 10 minutes restart the process |
| Back-pressure | A write blocked for 5 s means the process stopped reading: it is restarted, and its running calls fail |
| Concurrency | At most 16 requests per process. Concurrent operations share one start per key |
| Stop | Close stdin, wait 2 s, `SIGTERM` the process and every descendant, wait 5 s, `SIGKILL`. A failure skips the stdin step |

## Lifecycle

| Moment | Rule |
| --- | --- |
| Start | On the first `/v1/ops` or `/v1/warm` of a key, within 45 s, downloads included |
| Idle | Stopped after 15 minutes without an operation, and once idle after 24 hours |
| Capacity | At most 16 processes. A new key stops the idle process used the longest ago, never a busy one |
| Memory | Each process tree's resident memory is read every 5 s. A tree over 512 MiB is stopped |
| Failures | More than 3 in 10 minutes put the key in backoff for 10 minutes |
| SIGTERM | `/ready` fails at once. Requests are still answered for 5 s and running ones get up to 75 s, then every process is stopped |

`RLIMIT_NPROC` counts every thread of the pod's user, not one process. The value 1024 bounds a fork bomb without
starving 16 Node.js servers. The pod's memory limit is the hard bound.

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `OSTROM_MCP_HOST_TOKEN` | required | Shared with the server, at least 32 characters |
| `OSTROM_MCP_HOST_PORT` / `OSTROM_MCP_HOST_ADDRESS` | 8090 / 0.0.0.0 | Listening address |
| `OSTROM_MCP_HOST_DATA_DIR` | `/var/mcp` | Per-key directories |
| `OSTROM_MCP_HOST_LAUNCHERS` | `npm,pypi` | Allowed launchers |
| `OSTROM_MCP_HOST_NPM_REGISTRY` / `OSTROM_MCP_HOST_PYPI_INDEX` | npm's / PyPI's | Mirrors |
| `OSTROM_MCP_HOST_MAX_PROCESSES` | 16 | Processes at once |
| `OSTROM_MCP_HOST_MAX_IN_FLIGHT_PER_PROCESS` | 16 | Concurrent requests per process |
| `OSTROM_MCP_HOST_PROCESS_MEMORY_MIB` | 512 | Memory per process tree |
| `OSTROM_MCP_HOST_IDLE_MINUTES` | 15 | Idle stop |
| `OSTROM_MCP_HOST_START_TIMEOUT_MS` | 45000 | Start, downloads included |
| `OSTROM_MCP_HOST_DRAIN_SECONDS` | 75 | Time running operations get on SIGTERM. The pod's termination grace period must be longer |
| `OSTROM_MCP_HOST_RETAIN_GRACE_SECONDS` | 60 | A retain sweep spares a key first seen this recently (0 to 3600) |
| `OSTROM_MCP_HOST_PATH` | `/usr/local/bin:/usr/bin:/bin` | The only `PATH` processes see |
| `OSTROM_MCP_HOST_PRLIMIT` | `prlimit` | `none` runs without it (development machines) |

A bad configuration exits with code 2.

## Security

- The host receives the secrets of one process per request and keeps them in memory only.
- It never logs arguments, the environment, inputs, outputs or stderr. It logs one JSON event per line on stdout
  (`started`, `exited`, `killed`, `oversize`, `backoff`, `start_failed`), each with the key's first 12 characters,
  the launcher and the package.
- Accepted risk: processes share the pod and its UID, so a malicious pinned package could read its neighbours'
  environment through `/proc`. The mitigations:
  - only administrators declare these servers;
  - versions are exact and frozen at the declaration date;
  - install scripts never run;
  - each key has its own caches;
  - the pod has no database, key or cluster access, and a NetworkPolicy.

## Image

`mcp-host/Dockerfile` is built after `./gradlew :mcp-host:installHost`, with `mcp-host/` as the context. It holds:

- `eclipse-temurin:21-jre-noble`;
- Node.js 24, checked against the Node.js release keyring;
- uv, pinned by version and SHA-256;
- `tini` and `prlimit`;
- the host's jars;
- UID 10002;
- a JVM heap capped at 384 MiB.

The downloads happen in a separate stage, so the final image has neither `curl` nor `gpgv`.

`.github/workflows/mcp-host-image.yml` builds it for amd64 and arm64, then runs `scripts/smoke-mcp-host-image.py`
on the amd64 build. That test:

- runs the image as the chart will: UID 10002, a read-only root file system, no capability;
- checks that a missing or short token exits 2;
- checks the probes, the token and the protocol header;
- lists and calls `@modelcontextprotocol/server-everything@2.0.0` through `npx` and lists
  `mcp-server-fetch==2026.10.24` through `uvx`;
- checks that a version newer than the declaration date is refused;
- stops the container with SIGTERM.

The workflow pushes `latest` and the commit SHA from main only.

## Tests

`./gradlew :mcp-host:jvmTest` runs two kinds of tests:

- **`commonTest`** runs the supervisor, the transport and the routes against hand-written fakes (a Kotlin MCP
  server, a process table, files) under virtual time.
- **`jvmTest`** runs the real platform through `/bin/sh`, then TeamCode's Node.js fixture server
  (`stdio-fixture-server.js`) installed as `npx` and `uvx`. That covers listing, calling, environment isolation,
  scrubbing, crash and restart, PyPI, and a server that ignores `SIGTERM` and leaves a grandchild. Node.js must be
  on `PATH` (or in `OSTROM_MCP_HOST_TEST_NODE`).
