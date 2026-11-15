// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.presentation

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import run.ostrom.core.api.models.WorkflowEditors
import run.ostrom.core.api.models.WorkflowSettings
import run.ostrom.server.kernel.auth.GovernanceConfirmationPending
import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.empty
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.pathId
import run.ostrom.server.kernel.http.docs.queryLimit
import run.ostrom.server.kernel.http.docs.queryText

private const val TAG = "workflows"

private const val WORKFLOW_ID = "The workflow's id."

private const val WORKFLOW_NOT_FOUND = "No such workflow in the caller's organization (`not_found`)."

private const val PENDING = "Not applied: made with a person's API token (a personal token, the CLI's included), " +
    "the write opened a confirmation that an owner or an admin confirms on the console within 15 minutes " +
    "(`getGovernanceConfirmation`). A browser session's write is applied at once."

private const val CONFIRMATIONS = "The person already has 10 governance confirmations waiting " +
    "(`confirmation_limit`); expired, refused and answered ones do not count."

/** The workflows, and who may decide them. */
internal fun ApiCatalog.workflowOperations() {
    tag(TAG, "The workflows of an organization: what an agent does, step by step, and who may change it.")
    get("/api/v1/workflows") {
        operationId = "listWorkflows"
        tag(TAG)
        summary = "Workflows."
        parameters {
            organizationHeader()
            queryText("repo", "Only the workflows of this repository.")
            queryText("status", "Only the workflows in this status (`proposed`, `accepted` or `rejected`); 400 " +
                "otherwise.")
            queryText("after", "The `next` of the previous page: the id of its last workflow.")
            queryLimit("The page size, 1 to 100 (50 by default); 400 below.", maximum = 100)
        }
        responses {
            ok<WorkflowsPage>(
                "The workflows, oldest first, each with its version, author and date of last change; `next` set " +
                    "when another page may follow.",
            )
            badRequest("An unknown status, a limit below 1 or an `after` the organization has no workflow of " +
                "(`invalid_request`).")
        }
    }
    post("/api/v1/workflows") {
        operationId = "createWorkflow"
        tag(TAG)
        summary = "Create a workflow."
        description = "A person who decides (an owner or an admin, or an editor for a workflow without a gate node) " +
            "creates it as it is; anyone else only proposes one. A workflow that holds a gate node stays an " +
            "owner's or an admin's decision. Made with a person's API token, a write that decides opens a " +
            "confirmation instead."
        parameters {
            organizationHeader()
        }
        body<JsonObject>("The workflow: its id, name, nodes and edges.")
        responses {
            ok<JsonObject>("The workflow created.", HttpStatusCode.Created)
            ok<GovernanceConfirmationPending>(PENDING, HttpStatusCode.Accepted)
            badRequest("A workflow that does not hold together (`invalid_workflow`).")
            conflict("The name is taken, or the organization holds too many workflows (`workflow_name_taken`, " +
                "`workflow_limit`).")
            error(HttpStatusCode.TooManyRequests, CONFIRMATIONS)
        }
    }
    workflowItemOperations()
    workflowSettingsOperations()
}

/** One workflow. */
private fun ApiCatalog.workflowItemOperations() {
    get("/api/v1/workflows/{id}") {
        operationId = "getWorkflow"
        tag(TAG)
        summary = "A workflow."
        parameters {
            organizationHeader()
            pathId("id", WORKFLOW_ID)
        }
        responses {
            ok<JsonObject>("The workflow, with its version, author and date of last change.")
            notFound(WORKFLOW_NOT_FOUND)
        }
    }
    put("/api/v1/workflows/{id}") {
        operationId = "saveWorkflow"
        tag(TAG)
        summary = "Save a workflow; 409 `workflow_conflict` when `expectedVersion` is stale."
        description = "The workflow's own `id` is the path's. A person who decides saves it as it is; anyone else " +
            "only changes a proposed workflow into another proposal. A save that decides, made with a person's " +
            "API token, opens a confirmation instead."
        parameters {
            organizationHeader()
            pathId("id", WORKFLOW_ID)
        }
        body<SaveWorkflowRequest>("The workflow, and the version the editor read.")
        responses {
            ok<JsonObject>("The workflow saved, at its next version.")
            ok<GovernanceConfirmationPending>(PENDING, HttpStatusCode.Accepted)
            badRequest("A workflow that does not hold together, or whose id is not the path's (`invalid_workflow`).")
            notFound(WORKFLOW_NOT_FOUND)
            conflict("Someone saved the workflow in between (`workflow_conflict`), or its name is taken.")
            error(HttpStatusCode.TooManyRequests, CONFIRMATIONS)
        }
    }
    delete("/api/v1/workflows/{id}") {
        operationId = "deleteWorkflow"
        tag(TAG)
        summary = "Delete a workflow; 409 `workflow_in_use`."
        parameters {
            organizationHeader()
            pathId("id", WORKFLOW_ID)
        }
        responses {
            empty("The workflow is deleted.")
            ok<GovernanceConfirmationPending>(PENDING, HttpStatusCode.Accepted)
            notFound(WORKFLOW_NOT_FOUND)
            conflict("A pinned package compiles it, another workflow calls it or an automation starts it " +
                "(`workflow_in_use`).")
            error(HttpStatusCode.TooManyRequests, CONFIRMATIONS)
        }
    }
}

/** Who may decide workflows. */
private fun ApiCatalog.workflowSettingsOperations() {
    get("/api/v1/workflows/settings") {
        operationId = "getWorkflowSettings"
        tag(TAG)
        summary = "Who may decide workflows, and whether the caller may; every member reads it."
        parameters {
            organizationHeader()
        }
        responses {
            ok<WorkflowSettings>("The rule, and whether the caller may decide a workflow without a gate node.")
        }
    }
    put("/api/v1/workflows/settings") {
        operationId = "saveWorkflowSettings"
        tag(TAG)
        summary = "Change who may decide workflows."
        description = "An owner or an admin, in the console (a browser session); audited `workflow.editors.changed`. " +
            "`all` (every member), `admins` (the default: owners and admins only) or `members` with `memberIds` " +
            "(active members of the organization). An editor accepts, edits and deletes workflows as an admin " +
            "does; a workflow that holds a `gate` node stays an owner's or an admin's decision whatever the mode."
        parameters {
            organizationHeader()
        }
        body<WorkflowEditors>("The mode, and the members for `members`.")
        responses {
            ok<WorkflowSettings>("The rule saved.")
            badRequest("An unknown mode, a list outside `members`, more than 100 editors, or one who is not an " +
                "active member (`invalid_editors`).")
        }
    }
}
