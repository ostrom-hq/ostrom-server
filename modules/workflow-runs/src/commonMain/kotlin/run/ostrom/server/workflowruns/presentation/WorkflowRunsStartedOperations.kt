// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.presentation

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import run.ostrom.core.api.models.DeliverRequest
import run.ostrom.core.api.models.DeliverSessionRequest
import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.core.api.models.WorkflowSessionPending
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

private const val TAG = "workflows"

private const val RUN_ID = "The run's id."

private const val RUN_NOT_FOUND = "No such run in the caller's organization, or another member's (`not_found`)."

private const val STATUS = "Only the runs in this status: `queued`, `running`, `waiting`, `succeeded`, `failed` or " +
    "`cancelled`; 400 otherwise."

private const val BEFORE = "The `next` of the previous page: the id of its last run."

/** The runs of workflows, started by a person and read by any member. */
internal fun ApiCatalog.startedRunsOperations() {
    post("/api/v1/workflows/{id}/runs") {
        operationId = "startWorkflowRun"
        tag(TAG)
        summary = "Start a run."
        description = "Any member who writes workflows starts an accepted workflow as themselves, never with an " +
            "agent token; at most 20 unfinished runs per member (409 `run_limit`). The same `Idempotency-Key` " +
            "returns the first run (200, `Idempotency-Replayed: true`; a malformed key is 400 `invalid_request`)."
        parameters {
            organizationHeader()
            pathId("id", "The workflow's id.")
            headerText("Idempotency-Key", "1 to 128 characters of `A-Za-z0-9._:-`; one fresh key per press of " +
                "Start, reused for each retry of it.")
        }
        body<StartRunRequest>("The inputs the workflow asks for, and the profile its agent nodes run with.")
        responses {
            ok<WorkflowRun>("The first run of this `Idempotency-Key`, with `Idempotency-Replayed: true`.")
            ok<WorkflowRun>("The run started.", HttpStatusCode.Created)
            badRequest("A malformed key or input (`invalid_request`).")
            notFound("No such workflow in the caller's organization (`not_found`).")
            conflict("The workflow is not accepted, or the member has too many unfinished runs (`run_limit`).")
        }
    }
    get("/api/v1/workflows/{id}/runs") {
        operationId = "listWorkflowRuns"
        tag(TAG)
        summary = "Runs of a workflow."
        parameters {
            organizationHeader()
            pathId("id", "The workflow's id.")
            queryText("status", STATUS)
            queryText("before", BEFORE)
        }
        responses {
            ok<RunsPage>("A page of runs, `next` set when another may follow.")
            badRequest("An unknown status (`invalid_request`).")
        }
    }
    runOperations()
    runWriteOperations()
}

/** One run and the whole list. */
private fun ApiCatalog.runOperations() {
    get("/api/v1/runs") {
        operationId = "listRuns"
        tag(TAG)
        summary = "Runs."
        parameters {
            organizationHeader()
            queryFlag("all", "Every member's runs: an owner, an admin or a service key; the caller's own otherwise.")
            queryText("workflow", "Only the runs of this workflow.")
            queryText("status", STATUS)
            queryText("before", BEFORE)
            queryLimit("The page size, 1 to 100 (50 by default); 400 outside.", 100)
        }
        responses {
            ok<RunsPage>("A page of runs, `next` set when another may follow.")
            badRequest("An unknown status, a malformed cursor or a limit outside the bounds (`invalid_request`).")
            error(HttpStatusCode.Forbidden, "`all` asked by someone else than an owner, an admin or a service key.")
            notFound("No such workflow in the caller's organization (`not_found`).")
        }
    }
    get("/api/v1/runs/{id}") {
        operationId = "getRun"
        tag(TAG)
        summary = "A run with its visits."
        parameters {
            organizationHeader()
            pathId("id", RUN_ID)
        }
        responses {
            ok<WorkflowRun>("The run.")
            notFound(RUN_NOT_FOUND)
        }
    }
}

/** Cancelling a run and handing in its deliverable. */
private fun ApiCatalog.runWriteOperations() {
    post("/api/v1/runs/{id}/cancel") {
        operationId = "cancelRun"
        tag(TAG)
        summary = "Cancel a run."
        description = "The run's starter, or an owner or an admin, with a person's credential."
        parameters {
            organizationHeader()
            pathId("id", RUN_ID)
        }
        responses {
            ok<WorkflowRun>("The run, cancelled.")
            notFound(RUN_NOT_FOUND)
            conflict("The run has ended (`run_ended`), or changed meanwhile (`run_busy`).")
        }
    }
    post("/api/v1/runs/{id}/deliver") {
        operationId = "deliverRun"
        tag(TAG)
        summary = "Hand in what the run's current agent node asks for: the run's starter, a person or their key."
        description = "The key holds `workflows:write`. The output is checked against the node's deliverable: " +
            "`artifact` (an artifact id), `url` (https, on the node's `domain` or a subdomain of it when set), " +
            "`text`, or `json` matching its `schema` (a subset: `type`, an object's `properties` and `required`, " +
            "an array's `items`, a string's `enum`). A wrong one is 400 `invalid_deliverable` and stores nothing; a " +
            "run whose current node waits for no delivery is 409 `nothing_to_deliver`; a session whose todo still " +
            "has open items is 409 `todo_open`. Of two deliveries of one visit, one wins, the other is 409 " +
            "`nothing_to_deliver`. Another member's run, or one that does not exist, is 404."
        parameters {
            organizationHeader()
            pathId("id", RUN_ID)
        }
        body<DeliverRequest>("What the node asked for.")
        responses {
            ok<WorkflowRun>("The run after the delivery.")
            badRequest("An output that is not the deliverable the node asked for (`invalid_deliverable`).")
            notFound(RUN_NOT_FOUND)
            conflict("Nothing to deliver, or the session's todo is open (`nothing_to_deliver`, `todo_open`).")
        }
    }
}

/** The agent step of a session. */
internal fun ApiCatalog.workflowSessionOperations() {
    workflowsMcpOperations()
    post("/api/v1/workflows/session/deliver") {
        operationId = "deliverWorkflowSession"
        tag(TAG)
        summary = "Deliver for the agent step a session works on."
        description = "The run's starter naming `sessionId`, or the session's agent token with the scope " +
            "`workflow:session`. Finds the unfinished run whose latest active visit is an agent visit bound to the " +
            "session, then does exactly what `deliverRun` does (same checks, same errors). An " +
            "agent token is confined to its launch's session whatever `sessionId` says, and `workflow:session` " +
            "grants nothing else (not `deliverRun`, not a read of a run). A service key, or an agent token issued " +
            "before the scope existed, is 403 `credential_scope`. A session that does not exist or is another " +
            "member's is 404; the caller's own session that runs no step is 409 `nothing_to_deliver`."
        parameters {
            organizationHeader()
        }
        body<DeliverSessionRequest>("The session, for a person's call, and what the step asked for.")
        responses {
            ok<WorkflowRun>("The run after the delivery.")
            badRequest("An output that is not the deliverable the step asked for (`invalid_deliverable`).")
            notFound("No such session, or another member's (`not_found`).")
            conflict("Nothing to deliver, or the session's todo is open (`nothing_to_deliver`, `todo_open`).")
        }
    }
    get("/api/v1/workflows/session/pending") {
        operationId = "pendingWorkflowSession"
        tag(TAG)
        summary = "Whether the session's agent step still waits for its delivery, for the Stop hook."
        description = "`needsAction` is true when the session has an unfinished run whose latest active visit is an " +
            "agent visit bound to it and the session holds no open wait; it then names the run, the step's title " +
            "and the form of its deliverable (never its schema or its instruction). The same credentials and " +
            "session resolution as `deliverWorkflowSession`; the caller's own session that runs no step is 200 " +
            "with `needsAction: false`. Writes nothing."
        parameters {
            organizationHeader()
            queryText("sessionId", "Required for a person or a key; an agent token's own session otherwise.")
        }
        responses {
            ok<WorkflowSessionPending>("Whether the step waits for a delivery.")
            notFound("No such session, or another member's (`not_found`).")
        }
    }
}

/** The `ostrom-workflow` MCP server. */
private fun ApiCatalog.workflowsMcpOperations() {
    post("/api/v1/workflows/mcp") {
        operationId = "workflowsMcp"
        tag(TAG)
        summary = "The `ostrom-workflow` MCP server (JSON-RPC over HTTP): one tool, `deliver {output}`."
        description = "For the agent token of the session that works on an agent step (scope `workflow:session`). " +
            "Bridged to the agent by the CLI's session relay (`ostrom mcp --session ... --server " +
            "ostrom-workflow`), which names the session; the agent cannot name another. A cloud session's agent " +
            "token acts on its launch's cloud session whatever `session` names, and needs none. The tool tells " +
            "the agent to call it once, when the task of the current step is done, with exactly what the step " +
            "asked for, then to end its turn, and never for an ordinary session. Refusals (`invalid_deliverable`, " +
            "`nothing_to_deliver`, `todo_open`, `credential_scope`, `not_found`) come back as tool errors saying " +
            "what to do."
        parameters {
            organizationHeader()
            queryText("session", "The session, filled by the relay.")
        }
        body<JsonObject>("A JSON-RPC message.")
        responses {
            ok<JsonObject>("The JSON-RPC answer.")
            empty("A notification, nothing to answer.", HttpStatusCode.Accepted)
        }
    }
}
