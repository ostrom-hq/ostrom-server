# The runner of cloud sessions

`ostrom-runner` is the process of a cloud session's pod (module `runners` on the server side). It clones the
repository, opens a PTY running the agent (`/bin/sh -c` then a login shell once the agent exits), relays that
terminal to the server's WebSocket `/api/v1/cloud/runner/terminal`, and heartbeats the execution on
`/api/v1/cloud/runner/heartbeat`. It authenticates with its runner token, mounted as a file of the session's
Kubernetes Secret (`OSTROM_RUNNER_TOKEN_FILE`), or passed as a variable by the Docker runtime.

The image is built from `runner/Dockerfile`; the binary is native (Kotlin/Native, `linuxX64` and `linuxArm64`).

## Before cloud sessions are turned back on

Cloud sessions are off. These conditions come first:

- **S2-49: the agent runs as the same user as the runner.** The runner and the agent it starts share uid 1000 in
  the pod, so the agent can read the runner token file (and the runner's memory). It cannot see the token in its
  environment: the runner removes the variables in `RunnerConfig.PRIVATE_VARIABLES` before starting the agent.
  The agent must run as a separate user that cannot read the token, with the runner keeping the token to itself
  (for example a second uid in the pod's security context and a token file readable by the runner's uid only).
  Until that is done, a session's agent can use its runner token: heartbeat for its execution, or attach to its
  own terminal channel. It cannot reach another session.
