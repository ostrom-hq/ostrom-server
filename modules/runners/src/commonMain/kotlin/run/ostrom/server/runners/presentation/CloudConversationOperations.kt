// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import io.ktor.http.HttpStatusCode
import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.empty
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.headerText
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.pathId
import run.ostrom.server.kernel.http.docs.queryNumber
import run.ostrom.server.kernel.http.docs.queryText

private const val CONVERSATION = "`runners.cloud.conversation.v1`."

private const val PENDING = "The session already holds the most pending inputs it takes " +
    "(`too_many_pending_inputs`)."

/** The conversation of a cloud session, members' side: the transcript, the inputs and the agent's options. */
internal fun ApiCatalog.cloudConversationOperations() {
    transcriptOperations()
    inputReadOperations()
    inputSendOperations()
    inputAnswerOperations()
    agentOptionOperations()
}

/** The transcript: its pages, its stream and its subagents. */
private fun ApiCatalog.transcriptOperations() {
    transcriptPages()
    transcriptStream()
    subagentListing()
}

private fun ApiCatalog.transcriptPages() {
    get("/api/v1/cloud/sessions/{id}/events") {
        operationId = "listCloudEvents"
        tag(TAG)
        summary = "A page of a conversation session's transcript, in ascending order."
        description = "$CONVERSATION Owner, or an administrator of the organization. `firstSeq` is the oldest " +
            "retained event, `lastSeq` the session's newest; continue with `after=nextAfter` or " +
            "`before=nextBefore`. `feed` (`runners.cloud.transcript.feeds.v1`) keeps `main` (the main " +
            "agent's events) or one subagent's (the id of the `tool.call` that started it). A terminal " +
            "session has no transcript: an empty page. $ORG_RESOLUTION"
        parameters {
            organizationHeader()
            pathId("id", SESSION_ID)
            queryNumber("after", "Only the events after this sequence number.")
            queryNumber("before", "Only the events before this sequence number.")
            queryNumber("limit", "How many events a page holds (100 by default).")
            queryText("feed", "`main`, or the id of the `tool.call` that started a subagent.")
        }
        responses {
            ok<CloudEventPageDto>("A page of the transcript.")
            badRequest("A malformed cursor (`invalid_cursor`), a page size out of bounds (`invalid_limit`), or an " +
                "unknown feed (`invalid_feed`).")
            notFound(NO_SESSION)
        }
    }
}

private fun ApiCatalog.transcriptStream() {
    get("/api/v1/cloud/sessions/{id}/events/stream") {
        operationId = "streamCloudSession"
        tag(TAG)
        summary = "Server-sent events of a conversation session's transcript."
        description = "$CONVERSATION Each event is `id: <seq>`, `event: <type>`, `data:` the event as JSON; it " +
            "resumes after `Last-Event-ID` (or `?after=`). Keep-alive comments every 15 s, `retry: 2000`. " +
            "Ends with `event: end` once the session is terminated, or `event: denied` when the caller lost " +
            "access (checked again every 15 s). Carries every feed: each event names its own. $ORG_RESOLUTION"
        parameters {
            organizationHeader()
            pathId("id", SESSION_ID)
            queryNumber("after", "Resume after this sequence number; the `Last-Event-ID` header wins over it.")
            headerText("Last-Event-ID", "The `id` of the last event handled, as an `EventSource` resumes.")
        }
        responses {
            empty("The event stream (`text/event-stream`).", HttpStatusCode.OK)
            badRequest("A malformed cursor (`invalid_cursor`).")
            notFound(NO_SESSION)
        }
    }
}

private fun ApiCatalog.subagentListing() {
    get("/api/v1/cloud/sessions/{id}/agents") {
        operationId = "listCloudSubagents"
        tag(TAG)
        summary = "The session's subagents and their state, oldest first, the newest 200."
        description = "`runners.cloud.transcript.feeds.v1`. Kept by the server as events arrive: an agent appears " +
            "with its `tool.call` (`Agent`, or `Task`), counts its tool uses and ends with that call's " +
            "result; a live background task keeps it `running`. $ORG_RESOLUTION"
        parameters {
            organizationHeader()
            pathId("id", SESSION_ID)
        }
        responses {
            ok<CloudSubagentListDto>("The subagents, oldest first.")
            notFound(NO_SESSION)
        }
    }
}

/** Reading the session's inputs. */
private fun ApiCatalog.inputReadOperations() {
    get("/api/v1/cloud/sessions/{id}/messages") {
        operationId = "listCloudInputs"
        tag(TAG)
        summary = "The session's inputs (messages, interrupts, permission answers, task stops), newest first."
        description = "$CONVERSATION A page of `limit` inputs (50 by default) is followed by `nextBefore` while " +
            "there may be more. $ORG_RESOLUTION"
        parameters {
            organizationHeader()
            pathId("id", SESSION_ID)
            queryText("before", "The `nextBefore` of the previous page.")
            queryNumber("limit", "How many inputs a page holds.")
        }
        responses {
            ok<CloudInputPageDto>("A page of inputs, newest first.")
            badRequest("A malformed cursor (`invalid_cursor`) or a page size out of bounds (`invalid_limit`).")
            notFound(NO_SESSION)
        }
    }
    get("/api/v1/cloud/sessions/{id}/messages/{input}") {
        operationId = "getCloudInput"
        tag(TAG)
        summary = "One input of the session and its acknowledgement."
        description = "$CONVERSATION $ORG_RESOLUTION"
        parameters {
            organizationHeader()
            pathId("id", SESSION_ID)
            pathId("input", "The input's id.")
        }
        responses {
            ok<CloudInputDto>("The input, with how far it got.")
            notFound("No such session or input, or a malformed id (`not_found`).")
        }
    }
}

/** Sending a message and interrupting the agent. */
private fun ApiCatalog.inputSendOperations() {
    post("/api/v1/cloud/sessions/{id}/messages") {
        operationId = "sendCloudMessage"
        tag(TAG)
        summary = "Send a message to the agent of a conversation session."
        description = "$CONVERSATION The owner, or everyone who may use the session when `githubAccess` is " +
            "`shared`; the caller is always the author. Text 1 to 32,000 characters, body at most 256 KiB. " +
            "`Idempotency-Key` or `clientMessageId` (one, not both, 400 `invalid_idempotency_key`): a " +
            "retry returns the first input with `Idempotency-Replayed: true`, the same key with another " +
            "text is 422 `idempotency_conflict`. `expectedLastEventSeq` refuses it (409 `stale_view`) when " +
            "newer events exist. A message to a paused session resumes it unless `wake` is false; a " +
            "terminal session is 409 `terminal_session`, a terminated one 409 `session_not_running`. At " +
            "most 100 pending inputs per session (429 `too_many_pending_inputs`). Files are not served " +
            "yet: `fileIds` must stay empty (400 `files_unsupported`). Levels: `queued`, `delivered` " +
            "(handed to the runner), `consumed` (written to the agent, `eventSeq` points to its " +
            "`message.user` event), and the final `cancelled` or `rejected` with a `reason`; a delivery " +
            "not acknowledged within 60 s, or whose execution ended, is offered again, up to 3 attempts. " +
            ORG_RESOLUTION
        parameters {
            organizationHeader()
            pathId("id", SESSION_ID)
            headerText("Idempotency-Key", "1 to 200 visible characters; a retry of the message sends the same.")
        }
        body<SendCloudMessageBody>("The text, whether it wakes a paused session, and an optional key and view.")
        responses {
            ok<CloudInputDto>("Accepted; on a replay, the first acknowledgement.", HttpStatusCode.Accepted)
            badRequest("A message that does not fit (`invalid_message`), a malformed key " +
                "(`invalid_idempotency_key`) or files (`files_unsupported`).")
            notFound(NO_SESSION)
            conflict("Newer events exist (`stale_view`), or the session cannot take a message " +
                "(`terminal_session`, `session_not_running`).")
            error(HttpStatusCode.PayloadTooLarge, TOO_LARGE)
            error(HttpStatusCode.UnprocessableEntity, "The key was used with another message " +
                "(`idempotency_conflict`).")
            error(HttpStatusCode.TooManyRequests, PENDING)
        }
    }
    post("/api/v1/cloud/sessions/{id}/interrupt") {
        operationId = "interruptCloudSession"
        tag(TAG)
        summary = "Stop the agent's current turn."
        description = "$CONVERSATION Delivered before queued messages; never wakes a session. $ORG_RESOLUTION"
        parameters {
            organizationHeader()
            pathId("id", SESSION_ID)
        }
        responses {
            ok<CloudInputDto>("Accepted, as an input.", HttpStatusCode.Accepted)
            notFound(NO_SESSION)
            conflict("The session is not running with its runner ready (`nothing_to_interrupt`), or it cannot " +
                "take an input (`terminal_session`, `session_not_running`).")
            error(HttpStatusCode.TooManyRequests, PENDING)
        }
    }
}

/** Answering the agent's permission requests and stopping its tasks. */
private fun ApiCatalog.inputAnswerOperations() {
    post("/api/v1/cloud/sessions/{id}/permissions") {
        operationId = "answerCloudPermission"
        tag(TAG)
        summary = "Answer a permission request of the agent (a tool use to approve, or a question)."
        description = "`runners.cloud.permissions.v1`. Body at most 64 KiB, `Idempotency-Key` accepted. A " +
            "question is answered by whoever may message the session; a tool use is allowed or denied by " +
            "the owner or an administrator only (403 `permission_owner_only`). Never wakes a session: 409 " +
            "`nothing_to_answer` unless it runs. 409 `unknown_permission_request`, " +
            "`permission_request_answered`, `permission_request_expired` or `permission_request_ended`; 400 " +
            "`invalid_permission_answer` for indexes the request does not have, 400 " +
            "`permission_input_unseen` for an `allow` of a request whose input was left out of the event. " +
            "At most 16 pending answers per session. $ORG_RESOLUTION"
        parameters {
            organizationHeader()
            pathId("id", SESSION_ID)
            headerText("Idempotency-Key", "1 to 200 visible characters; a retry of the answer sends the same.")
        }
        body<AnswerCloudPermissionBody>("The request answered, and the answer.")
        responses {
            ok<CloudInputDto>("Accepted, as an input of kind `permission`.", HttpStatusCode.Accepted)
            badRequest("An answer the request does not take (`invalid_permission_answer`, " +
                "`permission_input_unseen`), or a malformed key (`invalid_idempotency_key`).")
            error(HttpStatusCode.Forbidden, "A tool use answered by someone but the owner or an administrator " +
                "(`permission_owner_only`).")
            notFound(NO_SESSION)
            conflict("Nothing to answer, or the request is unknown, answered, expired or ended " +
                "(`nothing_to_answer`, `unknown_permission_request`, `permission_request_answered`, " +
                "`permission_request_expired`, `permission_request_ended`).")
            error(HttpStatusCode.PayloadTooLarge, TOO_LARGE)
            error(HttpStatusCode.UnprocessableEntity, "The key was used with another answer " +
                "(`idempotency_conflict`).")
            error(HttpStatusCode.TooManyRequests, PENDING)
        }
    }
    post("/api/v1/cloud/sessions/{id}/tasks/stop") {
        operationId = "stopCloudTask"
        tag(TAG)
        summary = "Stop one of the agent's background tasks (a subagent, a background command, a monitor)."
        description = "`runners.cloud.tasks.v1`. Whoever may message the session. A `stop_task` input, delivered " +
            "before queued messages, never waking a session (409 `nothing_to_interrupt`); rejected " +
            "`task_stop_unsupported` for a runner that cannot stop tasks, `unknown_task` when the task " +
            "ended meanwhile. $ORG_RESOLUTION"
        parameters {
            organizationHeader()
            pathId("id", SESSION_ID)
        }
        body<StopCloudTaskBody>("The id of the task: 1 to 64 letters, digits, `_` and `-`.")
        responses {
            ok<CloudInputDto>("Accepted, as an input.", HttpStatusCode.Accepted)
            badRequest("A task id that does not fit (`invalid_task`).")
            notFound(NO_SESSION)
            conflict("The session is not running with its runner ready (`nothing_to_interrupt`), or it cannot " +
                "take an input (`terminal_session`, `session_not_running`).")
            error(HttpStatusCode.PayloadTooLarge, TOO_LARGE)
            error(HttpStatusCode.TooManyRequests, PENDING)
        }
    }
}
