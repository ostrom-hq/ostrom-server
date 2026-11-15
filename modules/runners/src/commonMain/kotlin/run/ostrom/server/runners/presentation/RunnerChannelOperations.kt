// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import io.ktor.http.HttpStatusCode
import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok

private const val BAD_EXECUTION = "A malformed session id (`invalid_request`)."

private const val NEVER_CACHED = "The answer is not cached (`Cache-Control: no-store`), and never logged or " +
    "journaled."

private const val PREPARATION_NOT_SERVED = "This server prepares no cloud session (`not_found`): the runner clones " +
    "as before."

/**
 * The channel the runner of a cloud session's execution calls: its sign of life, the tokens and values it asks for,
 * and its preparation. Every call names the session and the epoch of its execution in the body.
 */
internal fun ApiCatalog.runnerChannelOperations() {
    runnerHeartbeat()
    runnerGitHubToken()
    runnerVariables()
    runnerEnvironment()
    runnerPreparation()
    conditionally { runnerInference() }
}

private fun ApiCatalog.runnerHeartbeat() {
    post("/api/v1/cloud/runner/heartbeat") {
        operationId = "cloudRunnerHeartbeat"
        tag(TAG)
        summary = "The runner in a session's pod reports."
        description = "$RUNNER_TOKEN The body is at most 4 KiB. The answer is the instruction the runner follows " +
            "and the session's state."
        body<RunnerHeartbeatRequest>("The session and epoch the runner was started for, and what it knows of " +
            "itself.")
        responses {
            ok<RunnerHeartbeatResponse>("The instruction for the runner, and the session's state.")
            badRequest("A malformed session id or timestamp (`invalid_request`).")
            error(HttpStatusCode.Unauthorized, RUNNER_UNKNOWN)
            conflict(FENCED)
            error(HttpStatusCode.PayloadTooLarge, TOO_LARGE)
        }
    }
}

private fun ApiCatalog.runnerGitHubToken() {
    post("/api/v1/cloud/runner/github") {
        operationId = "fetchRunnerGithubToken"
        tag(TAG)
        summary = "The runner asks for the GitHub token its agent's `git` and `gh` use."
        description = "`runners.cloud.github.v1`. $RUNNER_TOKEN The user-to-server token of the member the session " +
            "runs for, refreshed on the server when it expires within an hour, scoped to what both the " +
            "App's installation and that member can reach. The answer says `not_linked` when the owner has " +
            "no usable link, the session's `githubAccess` is `none`, or the owner left the organization, and " +
            "`unavailable` when GitHub could not refresh it and no token stays valid for 5 more minutes. " +
            "Only the runner of the current execution of a running session gets it. $NEVER_CACHED " +
            "The token is never written to the session. The body is at most 4 KiB."
        body<RunnerGitHubTokenRequest>("The session and epoch the runner was started for.")
        responses {
            ok<RunnerGitHubTokenResponse>("The token and when it expires, or why there is none.")
            badRequest(BAD_EXECUTION)
            error(HttpStatusCode.Unauthorized, RUNNER_UNKNOWN)
            conflict(FENCED)
            error(HttpStatusCode.PayloadTooLarge, TOO_LARGE)
        }
    }
}

private fun ApiCatalog.runnerVariables() {
    post("/api/v1/cloud/runner/variables") {
        operationId = "fetchRunnerVariables"
        tag(TAG)
        summary = "The runner reads the environment variables of the member the session runs for."
        description = "`runners.cloud.variables.v1`. $RUNNER_TOKEN The runner starts the agent with them and serves " +
            "them to its Bash commands while it runs. Only the runner of the current execution of a session " +
            "that runs (`creating`, `resuming`, `running`, `pausing`). A disabled owner gets none. " +
            "$NEVER_CACHED The body is at most 4 KiB."
        body<RunnerExecutionBody>("The session and epoch the runner was started for.")
        responses {
            ok<RunnerVariablesResponse>("The variables, those that could not be resolved, and which are secrets.")
            badRequest(BAD_EXECUTION)
            error(HttpStatusCode.Unauthorized, RUNNER_UNKNOWN)
            conflict("$FENCED Or the session does not run (`variables_unavailable`).")
            error(HttpStatusCode.PayloadTooLarge, TOO_LARGE)
        }
    }
}

private fun ApiCatalog.runnerEnvironment() {
    post("/api/v1/cloud/runner/environment") {
        operationId = "fetchRunnerEnvironment"
        tag(TAG)
        summary = "The runner reads what to prepare."
        description = "`runners.cloud.environment.v1`. $RUNNER_TOKEN The session's recipe (`environment`), the Git " +
            "identity of the member the session runs for, and that member's environment variables, resolved " +
            "on every call. Only the runner of the current execution while the session is `creating` or " +
            "`resuming`. A recipe whose `requiredVariables` the owner lacks, or that `requireGitHub` without " +
            "a usable link, is `preparation_failed`, and the session fails (first execution) or pauses with " +
            "that reason (resume). The GitHub token is not in the answer: `git` and `gh` read it through " +
            "`fetchRunnerGithubToken`. $NEVER_CACHED The body is at most 4 KiB."
        body<RunnerExecutionBody>("The session and epoch the runner was started for.")
        responses {
            ok<RunnerEnvironmentResponse>("What to prepare, with the owner's values.")
            badRequest(BAD_EXECUTION)
            error(HttpStatusCode.Unauthorized, RUNNER_UNKNOWN)
            notFound(PREPARATION_NOT_SERVED)
            conflict("$FENCED Or the session no longer prepares (`not_preparing`), or its recipe cannot be " +
                "prepared (`preparation_failed`).")
            error(HttpStatusCode.PayloadTooLarge, TOO_LARGE)
        }
    }
}

private fun ApiCatalog.runnerPreparation() {
    post("/api/v1/cloud/runner/preparation") {
        operationId = "reportRunnerPreparation"
        tag(TAG)
        summary = "The runner reports its preparation."
        description = "`runners.cloud.environment.v1`. Same audience as `fetchRunnerEnvironment`. A `phase` " +
            "(`configuring_git`, `cloning_repositories`, `fetching_repositories`, `running_setup`, and " +
            "`securing_workspace` first after a transfer), shown on the session, or a failed step as " +
            "`{step, index, exitCode}` only. The server writes the session's message itself (no command " +
            "output or runner text reaches the API), sets `failure.code` to `preparation_failed` " +
            "(`retryable` for clone, fetch and GitHub failures), and stops the execution: a first execution " +
            "fails the session and deletes its workspace, a resume pauses it and keeps the workspace. " +
            "Returns the heartbeat's instruction. The body is at most 4 KiB."
        body<RunnerPreparationRequest>("The session and epoch, and the phase or the failed step.")
        responses {
            ok<RunnerHeartbeatResponse>("The instruction for the runner, and the session's state.")
            badRequest("A malformed session id, an unknown phase or a malformed failure (`invalid_request`).")
            error(HttpStatusCode.Unauthorized, RUNNER_UNKNOWN)
            notFound(PREPARATION_NOT_SERVED)
            conflict(FENCED)
            error(HttpStatusCode.PayloadTooLarge, TOO_LARGE)
        }
    }
}

/** The inference credential, which the module serves only on a server that issues them. */
private fun ApiCatalog.runnerInference() {
    post("/api/v1/cloud/runner/inference") {
        operationId = "issueRunnerInference"
        tag(TAG)
        summary = "The runner asks for the execution's inference credential."
        description = "`runners.cloud.inference.v1`. $RUNNER_TOKEN An agent token of the launch the runner opened " +
            "for this execution (`agent:session`), lasting one hour (`expiresInMillis`). The runner keeps it " +
            "in memory only, renews it after a third of its lifetime and forwards the agent's model calls " +
            "through its loopback proxy, so the agent only holds the proxy's random local token. The server " +
            "keeps at most the current credential and the one before it per execution, deletes them in the " +
            "write that ends the execution and purges expired ones every minute. Only the runner of the " +
            "current execution of a session that runs (`creating`, `resuming`, `running`, `pausing`) and " +
            "opened its launch. A server without the route answers 404: the agent then runs with its own " +
            "provider configuration. $NEVER_CACHED The body is at most 4 KiB."
        body<RunnerExecutionBody>("The session and epoch the runner was started for.")
        responses {
            ok<RunnerInferenceCredentialResponse>("The credential, when it expires and how long it lasts.")
            badRequest(BAD_EXECUTION)
            error(HttpStatusCode.Unauthorized, RUNNER_UNKNOWN)
            conflict("$FENCED Or the execution has no launch to run inference with (`inference_unavailable`): a " +
                "shell session, or no launch open.")
            error(HttpStatusCode.PayloadTooLarge, TOO_LARGE)
        }
    }
}
