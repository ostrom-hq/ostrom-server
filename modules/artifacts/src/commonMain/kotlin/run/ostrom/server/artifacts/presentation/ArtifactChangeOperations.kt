// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.presentation

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
import run.ostrom.server.kernel.http.docs.queryText
import io.ktor.http.HttpStatusCode

private const val TAG = "artifacts"

private const val ART_ID = "The artifact's id."

private const val BAD_MEMBER = "The member is not named by an id (`invalid_request`)."

private const val BUCKET_DOWN = "The artifact storage did not answer, or refused " +
    "(`artifact_storage_unavailable`)."

private const val INACTIVE = "Artifacts are not active for the organization: no bucket is stored " +
    "(`artifact_storage_missing`), the current one has not passed its test (`artifact_storage_unverified`), or " +
    "the switch is off (`artifacts_disabled`)."

private const val MEMBER_ID = "The member's id (`Member.id`, also `Me.member.id` for the caller), not a " +
    "login."

private const val NF = "No such artifact, or one the caller may not read, or no organization for the caller " +
    "(`not_found`)."

private const val OWNER_ONLY = "The caller reads the artifact but does not own it, whatever their role, or " +
    "the credential lacks `artifacts:write` (`forbidden`)."

private const val PERSON_403 = "Not a person: a browser session, the unlocked app or the CLI's sign-in, " +
    "never a token a program can present (`forbidden`)."

private const val READ_403 = "The credential lacks the `artifacts:read` scope (`forbidden`)."

private const val VERSION_NUMBER = "The version's number."

/**
 * The artifacts the caller owns and reads: changing and deleting one, its versions and files, who it is
 * shared with, the reading tickets of its pages, and the artifacts MCP server.
 */
internal fun ApiCatalog.artifactChangeOperations() {
    changeOperations()
    versionOperations()
    memberOperations()
    memberRemovalOperations()
    ticketOperations()
    mcpOperations()
}

/** Changing and deleting an artifact. */
private fun ApiCatalog.changeOperations() {
    patch("/api/v1/artifacts/{id}") {
        operationId = "updateArtifact"
        tag(TAG)
        summary = "Change an artifact's title or access."
        description = "A field left out stays as it is. The owner only: 403 for any other reader, an " +
            "administrator included. Changing `access` needs a person, a browser session, the unlocked app or " +
            "the CLI's sign-in, never a token a program can present, so an agent never makes an artifact " +
            "public; a title alone may come from a token with `artifacts:write`. Turning to `link` makes a new " +
            "public link, leaving it ends the link."
        parameters {
            organizationHeader()
            pathId("id", ART_ID)
        }
        body<UpdateArtifactRequest>("The new title, the new access, or both; what is left out stays as it " +
            "is.")
        responses {
            ok<ArtifactResponse>("The artifact, changed.")
            badRequest("A title that is empty, over 200 characters or with control characters, or an access " +
                "that is none of `personal`, `team` and `link` (`invalid_request`).")
            error(HttpStatusCode.Forbidden, "Not the owner, or a credential without `artifacts:write`, or a " +
                "change of access by anything but a person (`forbidden`).")
            notFound(NF)
            conflict(INACTIVE)
        }
    }
    delete("/api/v1/artifacts/{id}") {
        operationId = "deleteArtifact"
        tag(TAG)
        summary = "Delete an artifact."
        description = "The owner only: 403 for any other reader, an administrator included. Only from a " +
            "person, a browser session, the unlocked app or the CLI's sign-in, never a token a program can " +
            "present, so an agent never deletes an artifact. Its versions go with it, and its files from the " +
            "bucket. It works while artifacts are inactive, so an artifact can always be removed."
        parameters {
            organizationHeader()
            pathId("id", ART_ID)
        }
        responses {
            empty("The artifact is deleted.")
            error(HttpStatusCode.Forbidden, "Not the owner, or a credential without `artifacts:write`, or a " +
                "token a program can present (`forbidden`).")
            notFound(NF)
        }
    }
}

/** An artifact's versions and their files. */
private fun ApiCatalog.versionOperations() {
    get("/api/v1/artifacts/{id}/versions") {
        operationId = "listArtifactVersions"
        tag(TAG)
        summary = "List an artifact's versions."
        description = "Newest first. Every version is listed, so `next` is never set. Scope " +
            "`artifacts:read`."
        parameters {
            organizationHeader()
            pathId("id", ART_ID)
        }
        responses {
            ok<ArtifactVersionsResponse>("The versions, newest first.")
            error(HttpStatusCode.Forbidden, READ_403)
            notFound(NF)
            conflict(INACTIVE)
        }
    }
    get("/api/v1/artifacts/{id}/versions/{number}/file") {
        operationId = "getArtifactFile"
        tag(TAG)
        summary = "Read a file of a version."
        description = "Scope `artifacts:read`. The answer is the file's bytes in the file's own content " +
            "type, as an attachment, with `nosniff` and a content security policy that sandboxes it. `path` " +
            "names the file; the version's entry page when absent."
        parameters {
            organizationHeader()
            pathId("id", ART_ID)
            pathId("number", VERSION_NUMBER)
            queryText("path", "The file's path in the version; the entry page when absent.")
        }
        responses {
            empty("The file's bytes.", HttpStatusCode.OK)
            error(HttpStatusCode.Forbidden, READ_403)
            notFound("No such artifact, version or file, or one the caller may not read, or no organization " +
                "for the caller (`not_found`).")
            conflict(INACTIVE)
            error(HttpStatusCode.ServiceUnavailable, BUCKET_DOWN)
        }
    }
}

/** Who an artifact is shared with. */
private fun ApiCatalog.memberOperations() {
    get("/api/v1/artifacts/{id}/members") {
        operationId = "listArtifactMembers"
        tag(TAG)
        summary = "List the members an artifact is shared with."
        description = "Their ids. Scope `artifacts:read`."
        parameters {
            organizationHeader()
            pathId("id", ART_ID)
        }
        responses {
            ok<ArtifactMembersResponse>("The ids of the members it is shared with.")
            error(HttpStatusCode.Forbidden, READ_403)
            notFound(NF)
            conflict(INACTIVE)
        }
    }
    put("/api/v1/artifacts/{id}/members/{login}") {
        operationId = "shareArtifact"
        tag(TAG)
        summary = "Share an artifact with a member."
        description = "The owner only: 403 for any other reader, an administrator included. It works on " +
            "every access, `personal` included. A `personal` artifact stays `personal`, which already means its " +
            "owner and the members it is shared with (never an administrator for their role alone): sharing " +
            "never changes `access`. A `team` or `link` artifact is already readable by every member, so " +
            "sharing it only adds the member to the list."
        parameters {
            organizationHeader()
            pathId("id", ART_ID)
            pathId("login", MEMBER_ID)
        }
        responses {
            empty("The artifact is shared with the member.")
            badRequest(BAD_MEMBER)
            error(HttpStatusCode.Forbidden, OWNER_ONLY)
            notFound(NF)
            conflict(INACTIVE)
        }
    }
}

/** Ending the sharing of an artifact. */
private fun ApiCatalog.memberRemovalOperations() {
    delete("/api/v1/artifacts/{id}/members/{login}") {
        operationId = "unshareArtifact"
        tag(TAG)
        summary = "Stop sharing an artifact with a member."
        description = "The owner only: 403 for any other reader, an administrator included."
        parameters {
            organizationHeader()
            pathId("id", ART_ID)
            pathId("login", MEMBER_ID)
        }
        responses {
            empty("The artifact is no longer shared with the member.")
            badRequest(BAD_MEMBER)
            error(HttpStatusCode.Forbidden, OWNER_ONLY)
            notFound(NF)
            conflict(INACTIVE)
        }
    }
}

/** The reading tickets of an artifact's pages. */
private fun ApiCatalog.ticketOperations() {
    post("/api/v1/artifacts/{id}/tickets") {
        operationId = "createArtifactTicket"
        tag(TAG)
        summary = "Open a reading ticket for an artifact's page."
        description = "A sealed address of the artifact's entry page on the content origin, for the caller " +
            "only. For a person only: a browser session, the unlocked app or the CLI's sign-in. The ticket is " +
            "sealed (encrypted and authenticated, so the page learns nothing of its reader) and names the " +
            "member and the credential that asked: it lasts 8 hours (rounded down to 10 minutes, so reloading " +
            "reuses the same address), and the server checks again at most every 30 seconds that the credential " +
            "is still live, the member still in the organization and the artifact still readable by them, so " +
            "signing out, leaving the organization or losing access stops it within 30 seconds. At most 60 " +
            "tickets a minute per credential."
        parameters {
            organizationHeader()
            pathId("id", ART_ID)
        }
        body<TicketRequest>("The version to open; the latest when the body is empty or names none.", required = false)
        responses {
            ok<ArtifactTicketResponse>(
                "The ticket: the address of the entry page, until `expiresAt`.",
                HttpStatusCode.Created,
            )
            badRequest("A body that is not a JSON object with an optional whole `version` " +
                "(`invalid_request`).")
            error(HttpStatusCode.Forbidden, PERSON_403)
            notFound("No such artifact or version, or one the caller may not read, or no organization for " +
                "the caller (`not_found`).")
            conflict(INACTIVE)
            error(HttpStatusCode.TooManyRequests, "The credential opened 60 tickets this minute " +
                "(`rate_limited`); `Retry-After` says in how many seconds to try again.")
            error(HttpStatusCode.ServiceUnavailable, "This server serves no artifact pages: no content " +
                "origin is configured (`unavailable`).")
        }
    }
}

/** The artifacts MCP server. */
private fun ApiCatalog.mcpOperations() {
    post("/api/v1/artifacts/mcp") {
        operationId = "artifactsMcp"
        tag(TAG)
        summary = "Call the artifacts MCP server."
        description = "The artifacts MCP server, protocol 2025-06-18 on streamable HTTP with JSON answers " +
            "and no server-sent events. The body is one JSON-RPC message; a notification answers 202 with no " +
            "body. Its tools are `publish`, `list`, `read` and `share`, acting for the member whose credential " +
            "calls: `list` and `read` need `artifacts:read`, `publish` and `share` `artifacts:write`, which an " +
            "agent token holds, acting as the member who launched it. They never make an artifact public and " +
            "never delete one. A tool that fails answers 200 with a result whose `isError` is true; a message " +
            "that is not JSON, not a request, or names an unknown method or tool is a JSON-RPC error in a 200 " +
            "answer. `ostrom <agent>` connects an agent to it as `ostrom-artifacts`."
        parameters {
            organizationHeader()
        }
        responses {
            empty("The JSON-RPC answer, a result or an error, as JSON.", HttpStatusCode.OK)
            empty("A notification, accepted.", HttpStatusCode.Accepted)
            error(HttpStatusCode.PayloadTooLarge, "A body past what a full version needs in base64 " +
                "(`payload_too_large`).")
        }
    }
}
