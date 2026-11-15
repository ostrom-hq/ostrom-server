// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.admin.presentation

import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.empty
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.pathId
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Responses

private const val TAG = "admin"

private const val SECTION = "The section, a key such as `mail`, `push` or `google`."

/** The admin module's operations: the instance's administrators and its settings. */
internal fun ApiCatalog.adminOperations() {
    tag(TAG, "The instance's administrators and the settings that serve every organization.")
    instanceAdminsOperations()
    instanceSettingsOperations()
}

private fun ApiCatalog.instanceAdminsOperations() {
    get("/api/v1/admin/instance-admins") {
        operationId = "listInstanceAdmins"
        tag(TAG)
        summary = "The administrators of the instance."
        description = "A person only (a browser session, the unlocked app or the CLI's sign-in) who administers the " +
            "instance; no key reaches it, whatever its scopes (TeamCode's dashboard settings are an interactive " +
            "administrator's). The instance's administrators configure what serves every organization: " +
            "cloud-session clusters, the GitHub App, sign-in providers, mail, push, provider keys, analytics and " +
            "billing (TeamCode `docs/artifacts.md` \"Storage and settings\", `docs/cloud-sessions.md` " +
            "\"Resources\"). The person who used the instance's setup link (`ostrom-server bootstrap`) is the " +
            "first one; the former `OSTROM_INSTANCE_ADMINS` is imported once. 403 `instance_admin_required` for " +
            "anyone else."
        responses {
            ok<InstanceAdminListResponse>("The administrators.")
            notAnAdmin()
        }
    }
    post("/api/v1/admin/instance-admins") {
        operationId = "addInstanceAdmin"
        tag(TAG)
        summary = "Make a person an administrator of the instance."
        description = "A person who administers the instance. `email` is a verified address of an account of the " +
            "instance (404 `account_not_found` otherwise, the same answer for an unknown and an unverified " +
            "address). Adding an administrator twice changes nothing. Written to the instance's audit log."
        body<AddInstanceAdminRequest>("The verified address of the person to add.")
        responses {
            ok<InstanceAdminListResponse>("The administrators, the person added.")
            notAnAdmin()
            notFound("No account of the instance has this verified address (`account_not_found`).")
        }
    }
    delete("/api/v1/admin/instance-admins/{userId}") {
        operationId = "removeInstanceAdmin"
        tag(TAG)
        summary = "Remove an administrator of the instance."
        description = "A person who administers the instance. The last administrator is never removed (409 " +
            "`last_instance_admin`); a person who is not one gets 404 `instance_admin_not_found`. Written to the " +
            "instance's audit log."
        parameters { pathId("userId", "The administrator's account id.") }
        responses {
            empty("Removed.")
            notAnAdmin()
            notFound("This person is not an administrator of the instance (`instance_admin_not_found`).")
            conflict("The last administrator is never removed (`last_instance_admin`).")
        }
    }
}

private fun ApiCatalog.instanceSettingsOperations() {
    get("/api/v1/admin/settings") {
        operationId = "listInstanceSettings"
        tag(TAG)
        summary = "Every section of the instance's settings."
        description = "A person who administers the instance. The sections the server knows, each as " +
            "`getInstanceSettings` answers it: the settings that serve every organization, stored in PostgreSQL. " +
            "Nothing here is set in the environment or the Helm chart: only what the server needs to start is " +
            "(database, secrets key, public URLs, listen address, trusted proxies, per-replica ceilings, the MCP " +
            "host). Secrets are never returned."
        responses {
            ok<InstanceSettingsListResponse>("Every section.")
            notAnAdmin()
        }
    }
    get("/api/v1/admin/settings/{section}") {
        operationId = "getInstanceSettings"
        tag(TAG)
        summary = "One section of the instance's settings."
        description = "A person who administers the instance. Plain fields come back as stored; a secret field only " +
            "as `configured` (TeamCode's write-only secrets: sealed with the server's secrets key, AES-256-GCM, " +
            "bound to their section and field, never returned or shown again). A section the server does not " +
            "know is 404 `settings_section_not_found`."
        parameters { pathId("section", SECTION) }
        responses {
            ok<InstanceSettingsResponse>("The section.")
            notAnAdmin()
            notFound("The server has no such section (`settings_section_not_found`).")
        }
    }
    put("/api/v1/admin/settings/{section}") {
        operationId = "saveInstanceSettings"
        tag(TAG)
        summary = "Save one section of the instance's settings."
        description = "A person who administers the instance. `version` is the one replaced (0 before the first " +
            "save; 409 `settings_conflict` otherwise). `values` holds every plain field of the section, an absent " +
            "or empty one unset; a field the section does not have, or a value its rule refuses, is 400 " +
            "`invalid_settings` naming the field, never echoing a secret. `secrets` is write-only: a field absent " +
            "or empty keeps the stored secret, a value replaces it, `null` removes it. Every save is written to " +
            "the instance's audit log with the fields changed, never their values; every replica uses the new " +
            "settings within 30 seconds."
        parameters { pathId("section", SECTION) }
        body<SaveInstanceSettingsRequest>("The version replaced, the plain fields and the secrets to change.")
        responses {
            ok<InstanceSettingsResponse>("The section as saved.")
            badRequest("A field the section does not have, or a value its rule refuses (`invalid_settings`).")
            notAnAdmin()
            notFound("The server has no such section (`settings_section_not_found`).")
            conflict("`version` is not the stored one (`settings_conflict`).")
        }
    }
}

private fun Responses.Builder.notAnAdmin() =
    error(HttpStatusCode.Forbidden, "Not a person who administers the instance (`instance_admin_required`).")
