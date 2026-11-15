// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.secrets.presentation

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
import io.ktor.http.HttpStatusCode

private const val TAG = "secrets"

private const val ORG_NF = "The caller is not a member of an organization (`not_found`)."

private const val SCOPE_Q = "`org` (the default) for an organization secret, `personal` for the caller's " +
    "own."

private const val STEP = "A write from the console: a browser session of a person (`forbidden`), signed in " +
    "less than 5 minutes ago (`step_up_required`); sign in again, then send it again."

private const val S_403 = "Not a browser session of a person, or a credential without the scope " +
    "(`forbidden`), a member writing an organization secret (`forbidden`), or a sign-in older than 5 minutes " +
    "(`step_up_required`); sign in again, then send it again."

/**
 * The secrets module's operations: the organization's and the members' own secrets, whose values go in and
 * never come out, and the environment variants, assignments and exclusions that give each member their
 * environment variables. The answers are the module's own types.
 */
internal fun ApiCatalog.secretsOperations() {
    tag(TAG, "Secrets that are written and never read back, and the environment variables the members' " +
        "agents run with.")
    secretListOperations()
    secretChangeOperations()
    environmentOperations()
}

/** Listing and creating secrets. */
private fun ApiCatalog.secretListOperations() {
    get("/api/v1/secrets") {
        operationId = "listSecrets"
        tag(TAG)
        summary = "List the secrets, without their values."
        description = "Names and metadata only, by name. An owner or an admin sees every secret of the " +
            "organization, any other member the organization's and their own. Scope `secrets:read`; a device " +
            "token is refused."
        parameters {
            organizationHeader()
            queryText("after", "The `next` of the previous page; an opaque cursor.")
            queryLimit("The page size, 1 to 200 (50 by default); a larger one is brought down to " +
                "200.", maximum = 200)
        }
        responses {
            ok<SecretsPageResponse>("A page of secrets; `next` is set when the page is full.")
            badRequest("A `limit` of 0 or less, or a cursor this server did not return (`invalid_request`).")
            error(HttpStatusCode.Forbidden, "A credential without the scope `secrets:read`, or a device " +
                "token (`forbidden`).")
            notFound(ORG_NF)
        }
    }
    post("/api/v1/secrets") {
        operationId = "createSecret"
        tag(TAG)
        summary = "Create a secret."
        description = "`scope` is `org` (the default; owners and admins) or `personal` (the caller's own; " +
            "any member). The name is unique per owner, 1 to 128 characters of `A-Z a-z 0-9 _ . -`; the value " +
            "is 8 characters to 16 KiB without NUL, sealed with the instance key and never returned. Scope " +
            "`secrets:write`. " +
            STEP
        parameters {
            organizationHeader()
        }
        body<SecretWriteRequest>("The name, the value, and the scope (`org` by default).")
        responses {
            ok<SecretResponse>("The secret's metadata.", HttpStatusCode.Created)
            badRequest("A scope that is neither `org` nor `personal`, a name or a value out of its bounds " +
                "(`invalid_request`).")
            error(HttpStatusCode.Forbidden, S_403)
            notFound(ORG_NF)
            conflict("A secret with this name already exists for that owner (`conflict`).")
        }
    }
}

/** Rotating and deleting a secret. */
private fun ApiCatalog.secretChangeOperations() {
    put("/api/v1/secrets/{name}") {
        operationId = "rotateSecret"
        tag(TAG)
        summary = "Rotate a secret."
        description = "Replaces the value and bumps `version`. The `name` of the body must be the one of " +
            "the path. The organization's secret is rotated by an owner or an admin, a personal one by its " +
            "owner; the scope is the body's, else the query's. Scope `secrets:write`. " +
            STEP
        parameters {
            organizationHeader()
            pathId("name", "The secret's name.")
            queryText("scope", "Where to find the secret when the body names none: " +
                SCOPE_Q)
        }
        body<SecretWriteRequest>("The name again, the new value, and the scope.")
        responses {
            ok<SecretResponse>("The secret after the rotation.")
            badRequest("A name that differs from the path's, a scope that is neither `org` nor `personal`, " +
                "or a value out of its bounds (`invalid_request`).")
            error(HttpStatusCode.Forbidden, S_403)
            notFound("No such secret, or no organization for the caller (`not_found`).")
        }
    }
    delete("/api/v1/secrets/{name}") {
        operationId = "deleteSecret"
        tag(TAG)
        summary = "Delete a secret."
        description = "The organization's secret is deleted by an owner or an admin, a personal one by its " +
            "owner. Scope `secrets:write`. " +
            STEP
        parameters {
            organizationHeader()
            pathId("name", "The secret's name.")
            queryText("scope", SCOPE_Q)
        }
        responses {
            empty("The secret is deleted.")
            badRequest("A scope that is neither `org` nor `personal` (`invalid_request`).")
            error(HttpStatusCode.Forbidden, S_403)
            notFound("No such secret, or no organization for the caller (`not_found`).")
        }
    }
}
