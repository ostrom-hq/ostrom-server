// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.presentation

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
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
import run.ostrom.server.kernel.http.docs.queryFlag
import run.ostrom.server.kernel.http.docs.queryLimit
import run.ostrom.server.kernel.http.docs.queryText

private const val TAG = "waits"

private const val WAIT_ID = "The wait's id."

private const val WAIT_NOT_FOUND = "No such wait in the caller's organization, or one the caller may not read " +
    "(`not_found`)."

/** The waits ("Waiting for you"), the agents' MCP server and the wake of their sessions. */
internal fun ApiCatalog.waitsOperations() {
    waitReadOperations()
    waitAskOperations()
    waitAnswerOperations()
    waitAgentOperations()
    waitMcpOperations()
}

/** Listing and reading. */
private fun ApiCatalog.waitReadOperations() {
    get("/api/v1/waits") {
        operationId = "listWaits"
        tag(TAG)
        summary = "Waits; `mine` lists those for the caller."
        parameters {
            organizationHeader()
            queryText("status", "Only the waits in this status.")
            queryText("resolver", "`human` (by default: what \"Waiting for you\" shows), `time`, `event` or `all`; " +
                "400 otherwise. A wait for a time or an event waits for nobody, so it is listed only when asked for.")
            queryFlag("mine", "Only the waits for the caller.")
            queryText("assignee", "`all`, or a member id: the waits asked of them, for an administrator only (403 " +
                "otherwise). Without it, the waits asked of or by the caller.")
            queryText("sessionId", "Only that session's waits, asked of anyone: for the person who runs it only " +
                "(404 otherwise, an administrator included).")
            queryText("runId", "Only the waits of that workflow run.")
            queryText("before", "The `next` of the previous page: the id of its last wait.")
            queryLimit("The page size, 1 to 100 (50 by default); 400 outside.", maximum = 100)
        }
        responses {
            ok<WaitsResponse>("A page of waits, `next` set when another may follow.")
            badRequest("A malformed cursor, resolver or limit (`invalid_request`).")
            notFound("The session is not the caller's to read (`not_found`).")
        }
    }
    get("/api/v1/waits/{id}") {
        operationId = "getWait"
        tag(TAG)
        summary = "A wait."
        parameters {
            organizationHeader()
            pathId("id", WAIT_ID)
        }
        responses {
            ok<WaitResponse>("The wait.")
            notFound(WAIT_NOT_FOUND)
        }
    }
}

/** Asking. */
private fun ApiCatalog.waitAskOperations() {
    post("/api/v1/waits") {
        operationId = "createWait"
        tag(TAG)
        summary = "Ask a person (agent `ask_human`); `Idempotency-Key` header."
        description = "`assignee` is a member id or a username (at most 64 characters; an id wins over a username " +
            "that spells it), the caller by default; an unknown one is 400. A member asks in a session only when " +
            "they run it (403 `wait_session_owner_only`, an administrator included) and while it runs (409 " +
            "`session_not_running`). A `time` or `event` wait (`resolver`) is asked by an agent in its session " +
            "only, and resolves by itself: at `dueAt`, or when its `eventUrl` is called, or at `timeoutAt`; the " +
            "session's Stop hook wakes the agent with it (`POST /api/v1/waits/wake`). Nobody answers it (409 " +
            "`resolves_by_itself`); its asker cancels it. A `secret` field is refused in a session whose agent is " +
            "not Claude Code (422 `secret_unsupported_agent`: only Claude Code reads its environment again before " +
            "each command, so a value given later could reach no other agent)."
        parameters {
            organizationHeader()
            headerText("Idempotency-Key", "One key per question: the same key returns the first wait, answered " +
                "with `Idempotency-Replayed: true`.")
        }
        body<CreateWaitRequest>("The question, and for whom, in which session, or until when.")
        responses {
            ok<WaitResponse>("A replay of the same `Idempotency-Key`: the wait first created.")
            ok<WaitResponse>("The wait created.", HttpStatusCode.Created)
            badRequest("An unknown assignee, or a question or a field out of bounds (`invalid_request`).")
            notFound("No such session in the caller's organization (`not_found`).")
            conflict("The session is not running, or the caller has too many open waits (`conflict`).")
            error(HttpStatusCode.UnprocessableEntity, "A `secret` field in a session that cannot hold one " +
                "(`secret_unsupported_agent`, `secret_unsupported_session`).")
        }
    }
}

/** Answering and cancelling. */
private fun ApiCatalog.waitAnswerOperations() {
    post("/api/v1/waits/{id}/answer") {
        operationId = "answerWait"
        tag(TAG)
        summary = "Answer a wait; first answer wins (409 `wait_closed`)."
        description = "Only a person answers: the console, the unlocked app or the CLI's sign-in, never a program's " +
            "token. The answer stays on the wait and is handed once to the agent of its session, if any (`POST " +
            "/api/v1/waits/wake`). A wait of a cloud session that is paused (or pausing), not ended, resumes it, on " +
            "its owner's behalf and within the organization's limits (audited `cloud.session.woken`), and the " +
            "runner of its next execution starts the agent with the answer as its first message; a cloud session " +
            "that runs keeps the answer on the wait for its next execution (its Stop hook does not hold the " +
            "agent). A session that cannot resume (its organization out of hours or at a cap, its owner gone) stays " +
            "paused with the answer kept on the wait, and an ended one takes nothing. A wait for a time or an event " +
            "that resolves by itself wakes its session the same way. The value of a `secret` field travels in this " +
            "request and nowhere else: it is sealed apart in the same transaction (503 `secrets_unavailable` when " +
            "the server has no key to seal with), and the stored answer holds `provided` or `not provided` for the " +
            "field."
        parameters {
            organizationHeader()
            pathId("id", WAIT_ID)
        }
        body<AnswerWaitRequest>("The action chosen, and the values of the question's fields.")
        responses {
            ok<WaitResponse>("The wait, answered.")
            badRequest("An action or a value the question does not take (`invalid_request`).")
            notFound(WAIT_NOT_FOUND)
            conflict("The wait is already answered or cancelled (`wait_closed`), or resolves by itself.")
            error(HttpStatusCode.ServiceUnavailable, "The server has no key to seal a secret with " +
                "(`secrets_unavailable`).")
        }
    }
    post("/api/v1/waits/{id}/cancel") {
        operationId = "cancelWait"
        tag(TAG)
        summary = "Cancel a wait."
        description = "Its asker, the member asked or an administrator cancels it while it is open."
        parameters {
            organizationHeader()
            pathId("id", WAIT_ID)
        }
        responses {
            ok<WaitResponse>("The wait, cancelled.")
            notFound(WAIT_NOT_FOUND)
            conflict("The wait is already answered or cancelled (`wait_closed`).")
        }
    }
}

/** What agents and CI call: the event address, the MCP server and the wake. */
private fun ApiCatalog.waitAgentOperations() {
    post("/api/v1/waits/{id}/event") {
        operationId = "receiveWaitEvent"
        tag(TAG)
        summary = "The event an `event` wait waits for, from a CI or a script."
        description = "No sign-in: the wait's secret (`wev_...`, in its `eventUrl` once, at creation) is the proof, " +
            "in the `token` query parameter or the `Ostrom-Wait-Token` header. The server keeps only its SHA-256 " +
            "and compares it in constant time. An unknown wait, a wrong secret and a wait no longer open all " +
            "answer 404. A JSON body of at most 16 KiB (413 beyond); an empty body is `{\"result\": \"success\"}`. " +
            "At most 30 calls a minute per wait (429 `wait_event_rate_limit`). What it carries reaches the agent " +
            "as data from an outside caller, never as instructions. Call the server directly: an agent's relay " +
            "never carries this address."
        parameters {
            pathId("id", WAIT_ID)
            queryText("token", "The wait's secret, unless given in the `Ostrom-Wait-Token` header.")
            headerText("Ostrom-Wait-Token", "The wait's secret, unless given in the `token` query parameter.")
        }
        body<JsonObject>("What the event carries; empty for a success.", required = false)
        responses {
            ok<WaitEventReceivedResponse>(
                "The wait is resolved and its session will be woken (a paused cloud session resumes, as with " +
                    "`answerWait`).",
                HttpStatusCode.Accepted,
            )
            notFound("No such wait, a wrong secret, or a wait no longer open (`not_found`).")
            error(HttpStatusCode.PayloadTooLarge, "The body is over 16 KiB.")
            error(HttpStatusCode.TooManyRequests, "More than 30 calls a minute for this wait " +
                "(`wait_event_rate_limit`).")
        }
    }
}

/** The agents' MCP server and the wake of their sessions. */
private fun ApiCatalog.waitMcpOperations() {
    post("/api/v1/waits/mcp") {
        operationId = "waitsMcp"
        tag(TAG)
        summary = "The waits MCP server of an agent (Streamable HTTP, JSON answers), bound to its session."
        description = "Answers a notification with 202 and no body."
        parameters {
            organizationHeader()
            queryText("session", "The session the agent runs in; a cloud session's agent token is confined to its " +
                "cloud session whatever it names, and needs none.")
            queryText("run", "The workflow run, if any.")
        }
        body<JsonObject>("A JSON-RPC message.")
        responses {
            ok<JsonObject>("The JSON-RPC answer.")
            empty("A notification, nothing to answer.", HttpStatusCode.Accepted)
        }
    }
    post("/api/v1/waits/wake") {
        operationId = "wakeWaits"
        tag(TAG)
        summary = "What the waits of an agent's session have to say, for the Stop hook of its launch."
        description = "An agent token only: a launch on a machine through its local relay (the hooks' capability), " +
            "in one of its launch's sessions (`?session=`, any segment; 400 `invalid_request` without it); a cloud " +
            "session's agent token, from its runner, is confined to its cloud session whatever it names, and needs " +
            "no `session`. Like every route a cloud session's agent token calls, it answers only while the " +
            "execution its launch was opened in runs (403 otherwise). Holds the call up to `waitMillis` (0 to " +
            "30 000, 25 000 by default) and answers as soon as a wait of the session is answered: each answer is " +
            "handed over once (`messages`, oldest first, each starting with `[Ostrom wait answered]`). `open` says " +
            "a wait asked by the session is still open, so the hook keeps holding the agent; a session with no " +
            "open wait answers at once. With `waitMillis` 0 it never holds: a cloud session's runner asks so once " +
            "an execution has started, and starts the agent with the answers that arrived while the session was " +
            "paused as its first message."
        parameters {
            organizationHeader()
            queryText("session", "The session the agent runs in: any of its segments; a cloud session's agent " +
                "token needs none.")
        }
        body<WaitWakeRequest>("How long to hold the call; empty for the defaults.", required = false)
        responses {
            ok<WaitWakeResponse>("The answers that arrived, and whether a wait is still open.")
            badRequest("A malformed body, or no `session` (`invalid_request`).")
        }
    }
}
