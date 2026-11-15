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
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.pathId

private const val OWNER_ONLY = "Not the session's owner (`forbidden`)."

private const val OPTION_SHAPE = "1 to 120 letters, digits and `. _ : / -` (400 `invalid_agent`)."

/** The agent's options: the owner's Bypass, the model and the effort. */
internal fun ApiCatalog.agentOptionOperations() {
    permissionBypassOperation()
    agentModelOperation()
    agentEffortOperation()
}

private fun ApiCatalog.permissionBypassOperation() {
    put("/api/v1/cloud/sessions/{id}/permission-bypass") {
        operationId = "setCloudPermissionBypass"
        tag(TAG)
        summary = "The owner's standing approval of the session's tool uses (Bypass)."
        description = "`runners.cloud.permissions.bypass.v1`. While on, the server allows each tool-use " +
            "`permission.request` itself, as the owner, in the call that stores it; a question still waits " +
            "for a person. Idempotent and audited (`cloud.session.permission_bypass_enabled`, " +
            "`..._disabled`). The owner only (403 `bypass_owner_only`); 409 `session_not_running` once " +
            "terminated. The body is at most 64 KiB. $ORG_RESOLUTION"
        parameters {
            onBehalfHeader(ownerOnly = true)
            organizationHeader()
            pathId("id", SESSION_ID)
        }
        body<SetCloudPermissionBypassBody>("Whether the standing approval is on.")
        responses {
            ok<CloudSessionResponse>("The session, with its Bypass.")
            error(HttpStatusCode.Forbidden, "Not the session's owner (`bypass_owner_only`).")
            notFound(NO_SESSION)
            conflict("The session is terminated (`session_not_running`).")
        }
    }
}

private fun ApiCatalog.agentModelOperation() {
    put("/api/v1/cloud/sessions/{id}/model") {
        operationId = "setCloudSessionModel"
        tag(TAG)
        summary = "Change the model of the session's agent, while it runs too."
        description = "`runners.cloud.model.v1`. The owner only. $OPTION_SHAPE Recorded at once (audited " +
            "`cloud.session.model_changed`, the same model writes nothing), so a paused session starts " +
            "with it; a running conversation agent also gets a `set_model` input, taken at its next turn. " +
            "A terminal session's agent gets it at its next start. 409 `session_not_running` once " +
            "terminated. The body is at most 64 KiB. $ORG_RESOLUTION"
        parameters {
            organizationHeader()
            pathId("id", SESSION_ID)
        }
        body<SetCloudModelBody>("The model.")
        responses {
            ok<CloudSessionResponse>("The session, with its model.")
            badRequest("A model that does not fit (`invalid_agent`).")
            error(HttpStatusCode.Forbidden, OWNER_ONLY)
            notFound(NO_SESSION)
            conflict("The session is terminated (`session_not_running`).")
        }
    }
}

private fun ApiCatalog.agentEffortOperation() {
    put("/api/v1/cloud/sessions/{id}/effort") {
        operationId = "setCloudSessionEffort"
        tag(TAG)
        summary = "Change the effort (reasoning level) of the session's agent, while it runs too."
        description = "`runners.cloud.effort.v1`, as `setCloudSessionModel` for the effort: $OPTION_SHAPE " +
            "Clients suggest `low`, `medium`, `high`, `xhigh` and `max` for Claude Code, `low`, `medium`, " +
            "`high` and `xhigh` for Codex. Audited `cloud.session.effort_changed`; a running conversation " +
            "agent gets a `set_effort` input. The body is at most 64 KiB. $ORG_RESOLUTION"
        parameters {
            organizationHeader()
            pathId("id", SESSION_ID)
        }
        body<SetCloudEffortBody>("The effort.")
        responses {
            ok<CloudSessionResponse>("The session, with its effort.")
            badRequest("An effort that does not fit (`invalid_agent`).")
            error(HttpStatusCode.Forbidden, OWNER_ONLY)
            notFound(NO_SESSION)
            conflict("The session is terminated (`session_not_running`).")
        }
    }
}
