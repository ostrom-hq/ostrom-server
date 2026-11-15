// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sessions.presentation

import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.empty
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.pathId
import run.ostrom.server.kernel.http.docs.queryFlag
import run.ostrom.server.kernel.http.docs.queryNumber
import run.ostrom.server.kernel.http.docs.queryText
import run.ostrom.server.kernel.http.docs.rateLimited

private const val TAG = "sessions"

private const val SEGMENT_ID = "The segment's id (the agent's session id, or a resumed segment's own id)."

private const val NOT_FOUND = "No such segment in the caller's organization, or one the caller may not read " +
    "(`not_found`)."

private const val ENDED = "The segment has ended (`session_ended`)."

private const val CLOSED = "The segment is closed (`conflict`, its message starting `session closed:`): its " +
    "launcher reopens a segment and sends it there."

/**
 * The sessions module's operations: the segments of the coding agent sessions, from their start to their signed
 * receipt. The bodies are the module's own mirrors of ostrom-core's `Session*` models.
 */
internal fun ApiCatalog.sessionsOperations() {
    tag(TAG, "The coding agent sessions: their segments, signs of life, activity and signed receipts.")
    sessionReadOperations()
    sessionStartOperations()
    sessionEndOperations()
    sessionReportOperations()
}

/** Listing and reading segments. */
private fun ApiCatalog.sessionReadOperations() {
    get("/api/v1/sessions") {
        operationId = "listSessions"
        tag(TAG)
        summary = "Sessions."
        description = "The segments of the caller's organization the caller may read, newest first."
        parameters {
            organizationHeader()
            queryText("repo", "Only this repository's.")
            queryText("dev", "Only this person's.")
            queryFlag("open", "Only the open (true) or the ended (false) ones.")
            queryText("before", "The cursor of the next page (`next` of the previous one).")
            queryText("label", "`key=value`, repeatable (at most 16): only the sessions whose launch carries " +
                "every label given (`sessions.labels.v1`, TeamCode's `GET /runs?label=k=v`). The key is " +
                "everything before the first `=`. It composes with the other filters and never widens what the " +
                "caller may read. 400 `invalid_label` for a malformed one.")
            queryNumber("limit", "The page size, 1 to 100 (50 by default); a value outside is brought into it.")
        }
        responses {
            ok<SessionsPage>("A page of segments, `next` set when another may follow.")
            badRequest("A malformed label (`invalid_label`) or cursor (`invalid_request`).")
            rateLimited()
        }
    }
    get("/api/v1/sessions/{id}") {
        operationId = "getSession"
        tag(TAG)
        summary = "A session segment and its receipt."
        description = "Read by the session's person, an agent token of its launch, and the approvers of its gates " +
            "(read only), who get the segment whole. An owner or an administrator reads another member's session as " +
            "its record only, as TeamCode's run records: `gates`, `gateAnswers` and `receipt` are absent, " +
            "`receiptId` names the receipt they do not read. Anyone else gets 404."
        parameters {
            organizationHeader()
            pathId("id", SEGMENT_ID)
        }
        responses {
            ok<SessionResponse>("The segment, with its receipt once ended.")
            notFound(NOT_FOUND)
            rateLimited()
        }
    }
}

/** Starting a segment and keeping it alive. */
private fun ApiCatalog.sessionStartOperations() {
    post("/api/v1/sessions") {
        operationId = "startSession"
        tag(TAG)
        summary = "Start a session or a segment (hook SessionStart); a resumed session opens a chained segment."
        description = "The person is the credential's, never the body's (`dev` is ignored). A session never takes " +
            "the id of another person's cloud session: 403 `forbidden` (\"This session is not yours.\")."
        parameters {
            organizationHeader()
        }
        body<StartSessionRequest>("The agent's session id, its launch and where it runs.")
        responses {
            ok<SessionResponse>("The segment started, or the open one of that id.")
            badRequest("A malformed session id, or no agent (`invalid_request`).")
            conflict("Another launch resumed the session (`conflict`).")
        }
    }
    post("/api/v1/sessions/{id}/alive") {
        operationId = "sessionAlive"
        tag(TAG)
        summary = "Sign of life of the launcher (every 20 s)."
        description = "With `sessions.activity.v1`, a sign of life that ends a silence of the launcher (60 s without " +
            "one) clears a `working` activity reported before the silence, and keeps `waiting_for_human`. The " +
            "answer's `ended` tells a launcher whose segment was closed for lack of a sign of life (`lost`) to " +
            "reopen one."
        parameters {
            organizationHeader()
            pathId("id", SEGMENT_ID)
        }
        responses {
            ok<SessionDto>("The segment.")
            notFound(NOT_FOUND)
        }
    }
}

/** Ending a segment and reopening one. */
private fun ApiCatalog.sessionEndOperations() {
    post("/api/v1/sessions/{id}/end") {
        operationId = "endSession"
        tag(TAG)
        summary = "End a session: the server signs the segment receipt."
        description = "The cost is the server's own measure; the request's `costUsd` is never read."
        parameters {
            organizationHeader()
            pathId("id", SEGMENT_ID)
        }
        body<EndSessionRequest>("What the launcher counted, and why the session ended.")
        responses {
            ok<SessionResponse>("The segment ended, with its signed receipt.")
            notFound(NOT_FOUND)
        }
    }
    post("/api/v1/sessions/{id}/reopen") {
        operationId = "reopenSession"
        tag(TAG)
        summary = "Reopen a closed session as a new segment chained to its receipt (launcher woke up)."
        parameters {
            organizationHeader()
            pathId("id", SEGMENT_ID)
        }
        responses {
            ok<SessionResponse>("The segment now open; `reopened` false when it was already open.")
            notFound(NOT_FOUND)
            conflict("Another launch resumed the session (`conflict`).")
        }
    }
}

/** What the agent's hooks report on a segment. */
private fun ApiCatalog.sessionReportOperations() {
    post("/api/v1/sessions/{id}/activity") {
        operationId = "reportSessionActivity"
        tag(TAG)
        summary = "Whether the agent works or waits for a person (`sessions.activity.v1`), reported by its hooks."
        description = "TeamCode's `POST /api/v1/runs/{id}/activity` on Ostrom's session segment: the launch of " +
            "`ostrom <agent>` reports from the agent's `Stop`, `Notification`, `UserPromptSubmit` and `PostToolUse` " +
            "hooks, so the sessions list tells a session that needs a person from one that is busy " +
            "(`Session.activity`). Callers: the session's person or an agent token of its launch (scope " +
            "`sessions:write` or the agent token's): another person, or another launch's agent token, gets 403; an " +
            "unknown segment, or one of another organization, 404. Reports are applied one at a time per segment. A " +
            "report observed before the stored one changes nothing (`superseded: true`). A repeated state keeps its " +
            "time (`since`). Not audited, like a sign of life."
        parameters {
            organizationHeader()
            pathId("id", SEGMENT_ID)
        }
        body<SessionActivityRequest>("The state, and when the hook observed it.")
        responses {
            ok<SessionActivityDto>("The stored activity; `superseded` when a later report was kept.")
            badRequest("Another state, or an `observedAt` that is not a date-time (`invalid_session_activity`).")
            notFound(NOT_FOUND)
            conflict("$ENDED A lost one too: its launcher reports to the segment it reopens.")
        }
    }
    post("/api/v1/sessions/{id}/seen") {
        operationId = "reportSeen"
        tag(TAG)
        summary = "A skill or sub-agent of the package was opened."
        parameters {
            organizationHeader()
            pathId("id", SEGMENT_ID)
        }
        body<SeenRequest>("The skill or sub-agent, and how it was seen.")
        responses {
            empty("Recorded (an item already seen is recorded once).")
            notFound(NOT_FOUND)
            conflict(CLOSED)
        }
    }
}
