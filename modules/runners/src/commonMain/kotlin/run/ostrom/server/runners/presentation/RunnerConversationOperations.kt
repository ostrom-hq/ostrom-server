// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import io.ktor.http.HttpStatusCode
import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.empty
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.ok

private const val CONVERSATION = "`runners.cloud.conversation.v1`."

private const val BAD_BATCH = "A malformed session id (`invalid_request`)."

/**
 * The agent channel of the runner of a conversation session: how to start its agent, the transcript events it
 * uploads, and the inputs it polls and acknowledges. Another epoch or an unknown credential is 409
 * `execution_fenced`.
 */
internal fun ApiCatalog.runnerConversationOperations() {
    runnerAgent()
    runnerEvents()
    runnerInputs()
    runnerInputAcknowledgement()
}

private fun ApiCatalog.runnerAgent() {
    post("/api/v1/cloud/runner/agent") {
        operationId = "cloudRunnerAgent"
        tag(TAG)
        summary = "The runner of a conversation session reads how to start its agent."
        description = "$CONVERSATION $RUNNER_TOKEN A terminal session is 409 `terminal_session`. The answer's " +
            "flags say what the server takes: `permissionRelay` (`permission.request` events), `liveText` " +
            "(`message.agent.delta`), `backgroundTasks` (`tasks` events); a runner never sends an event " +
            "type the server did not announce, which would refuse the batch. `conversation` says what the " +
            "last execution left: `resumable` (the agent's own conversation is kept in the workspace: " +
            "resume it), `transcript_only` (only the transcript is kept: start a fresh one) or `none` " +
            "(the agent never ran). The body is at most 4 KiB."
        body<RunnerAgentRequest>("The session and epoch the runner was started for.")
        responses {
            ok<RunnerAgentResponse>("The agent to start, and what the server takes.")
            badRequest(BAD_BATCH)
            error(HttpStatusCode.Unauthorized, RUNNER_UNKNOWN)
            conflict("$FENCED Or the session is a terminal session (`terminal_session`).")
            error(HttpStatusCode.PayloadTooLarge, TOO_LARGE)
        }
    }
}

private fun ApiCatalog.runnerEvents() {
    post("/api/v1/cloud/runner/events") {
        operationId = "cloudRunnerEvents"
        tag(TAG)
        summary = "The runner uploads a batch of the agent's normalized transcript events."
        description = "$CONVERSATION $RUNNER_TOKEN `sequence` is the runner's own consecutive counter per epoch, " +
            "from 1: a retried batch is deduplicated. At most 200 events and 2 MB. The server bounds and " +
            "masks each event again. A `turn` `started` makes the session's agent busy, `completed`, " +
            "`interrupted` or `failed` idle again; both count as activity. A `status` with `conversation` " +
            "(`resumable`, `transcript_only` or `none`) records whether the next execution can resume the " +
            "agent's own conversation, as `cloudRunnerAgent` then answers."
        body<RunnerEventsRequest>("The session and epoch, and the events, each with its sequence number.")
        responses {
            ok<RunnerEventsResponse>("The last sequence stored, and the session's newest event.")
            badRequest("An unknown event type or a malformed batch (`invalid_events`, `invalid_request`).")
            error(HttpStatusCode.Unauthorized, RUNNER_UNKNOWN)
            conflict(FENCED)
            error(HttpStatusCode.PayloadTooLarge, TOO_LARGE)
        }
    }
}

private fun ApiCatalog.runnerInputs() {
    post("/api/v1/cloud/runner/inputs") {
        operationId = "cloudRunnerInputs"
        tag(TAG)
        summary = "The runner long-polls the session's queued inputs; they come back delivered."
        description = "$CONVERSATION $RUNNER_TOKEN Waits up to `waitMillis` (at most 20,000). Interrupts, task " +
            "stops and permission answers come before messages. Nothing is delivered unless the session " +
            "runs and is desired running. With `desiredStatus` (the one the runner last knew), the poll " +
            "also ends as soon as the session's differs, so a pause reaches the runner within half a " +
            "second. An input kind the runner does not announce (`acceptsFiles`, `acceptsTaskStops`, " +
            "`acceptsModelChanges`, `acceptsEffortChanges`) is rejected for it instead of being delivered. " +
            "The body is at most 4 KiB."
        body<RunnerInputsRequest>("The session and epoch, how long to wait, and what the runner accepts.")
        responses {
            ok<RunnerInputsResponse>("The inputs delivered, and the session's desired status.")
            badRequest(BAD_BATCH)
            error(HttpStatusCode.Unauthorized, RUNNER_UNKNOWN)
            conflict(FENCED)
            error(HttpStatusCode.PayloadTooLarge, TOO_LARGE)
        }
    }
}

private fun ApiCatalog.runnerInputAcknowledgement() {
    post("/api/v1/cloud/runner/inputs/ack") {
        operationId = "cloudRunnerInputsAck"
        tag(TAG)
        summary = "The runner acknowledges inputs it handed to the agent (consumed) or refused (rejected)."
        description = "$CONVERSATION $RUNNER_TOKEN A consumed message becomes its `message.user` event; a consumed " +
            "permission answer an `input` event. A repeat is accepted and changes nothing. At most 100 " +
            "acknowledgements, the body at most 64 KiB."
        body<RunnerInputsAckRequest>("The session and epoch, and each input consumed or rejected.")
        responses {
            empty("Recorded.")
            badRequest("An acknowledgement that is neither consumed nor rejected, or a malformed input id " +
                "(`invalid_acks`).")
            error(HttpStatusCode.Unauthorized, RUNNER_UNKNOWN)
            conflict(FENCED)
            error(HttpStatusCode.PayloadTooLarge, TOO_LARGE)
        }
    }
}
