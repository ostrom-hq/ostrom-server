// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import io.ktor.http.HttpStatusCode
import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.headerText
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.pathId
import run.ostrom.server.kernel.http.docs.queryNumber
import run.ostrom.server.kernel.http.docs.queryText

private const val STATE_CHANGE = "A session whose state does not allow it, or that changed meanwhile " +
    "(`conflict`, `session_busy`)."

/** The cloud sessions a member manages: listing, creating, reading, stopping, pausing, resuming, and their access. */
internal fun ApiCatalog.cloudSessionOperations() {
    cloudSessionListing()
    cloudSessionCreation()
    cloudSessionReading()
    cloudSessionStop()
    cloudSessionPause()
    cloudSessionResume()
    cloudSessionHosting()
    cloudSessionGitHubAccess()
}

private fun ApiCatalog.cloudSessionListing() {
    get("/api/v1/cloud/sessions") {
        operationId = "listCloudSessions"
        tag(TAG)
        summary = "The caller's cloud agent sessions, newest first."
        description = "With `runners.cloud.remote.v1`, `kind` (repeatable) chooses the kinds listed, `cloud` or " +
            "`remote` (400 `invalid_kind` otherwise); without it, cloud sessions only, so a client from " +
            "before remote sessions never lists one it cannot show. A page of `limit` sessions (20 by " +
            "default) is followed by `next` while there may be more. $ORG_RESOLUTION"
        parameters {
            organizationHeader()
            queryText("kind", "The kind of session to list, `cloud` or `remote`; repeat it to list both.")
            queryText("before", "The `next` of the previous page.")
            queryNumber("limit", "How many sessions a page holds.")
        }
        responses {
            ok<CloudSessionsResponse>("A page of the caller's sessions, newest first, with the cursor of the next.")
            badRequest("An unknown `kind` (`invalid_kind`).")
            notFound("The caller is no member of an organization the request can act in (`not_found`).")
        }
    }
}

private fun ApiCatalog.cloudSessionCreation() {
    post("/api/v1/cloud/sessions") {
        operationId = "createCloudSession"
        tag(TAG)
        summary = "Start a cloud agent session."
        description = "`kind` is `cloud` (the default: a pod the server starts) or `remote` " +
            "(`runners.cloud.remote.v1`: a session on the caller's own device, with its own fields). A cloud " +
            "session takes none of a remote session's fields (400 `invalid_session`); a remote session takes " +
            "no repository, environment, profile or image (400 `invalid_session`) and always runs in mode " +
            "`conversation` (400 `invalid_mode`). With an `Idempotency-Key` (`runners.cloud.idempotency.v1`), " +
            "the key is scoped to the organization, the initiating member and the route, and the server " +
            "stores a SHA-256 of the canonical request in the transaction that creates the session, for 24 " +
            "hours. The same key with the same request answers `202` with the session's current view and " +
            "`Idempotency-Replayed: true`, creating nothing; the same key with another request is 422 " +
            "`idempotency_conflict`; concurrent requests with one key create one session and the others " +
            "replay. A malformed key is 400 `invalid_request`. The key is optional. $ORG_RESOLUTION"
        parameters {
            onBehalfHeader()
            organizationHeader()
            headerText(
                "Idempotency-Key",
                "1 to 128 characters of `A-Za-z0-9._:-`; one fresh key per press of Start, reused for each retry " +
                    "of it.",
            )
        }
        body<CreateCloudSessionRequest>("The agent, and what it starts with.")
        responses {
            ok<CloudSessionResponse>("The session, created.", HttpStatusCode.Created)
            ok<CloudSessionResponse>(
                "The session this `Idempotency-Key` created, as it is now, with `Idempotency-Replayed: true`.",
                HttpStatusCode.Accepted,
            )
            badRequest("A malformed `Idempotency-Key`, an unknown `kind`, or fields that do not fit the kind " +
                "(`invalid_request`, `invalid_kind`, `invalid_session`, `invalid_mode`).")
            error(HttpStatusCode.PaymentRequired, "The plan runs as many cloud sessions as it allows, or its " +
                "cloud hours of the period are used up (`payment_required`).")
            error(HttpStatusCode.Forbidden, "A cloud profile not assigned to the owner (`profile_unassigned`), an " +
                "image the profile does not allow (`image_not_allowed`), or cloud sessions closed to the " +
                "organization (`forbidden`).")
            notFound("No cloud profile has the `profileId` (`cloud_profile_not_found`), or the caller is no " +
                "member of an organization the request can act in (`not_found`).")
            conflict("The profile's cluster is gone or its namespace is no longer allowed (`cluster_unavailable`, " +
                "`namespace_not_allowed`).")
            error(HttpStatusCode.UnprocessableEntity, "The `Idempotency-Key` was used with another request " +
                "(`idempotency_conflict`), no profile was named while the owner may use several or none " +
                "(`profile_required`), a remote session without a device (`remote_requires_device`), or a session " +
                "created for another member with an environment that needs GitHub, which its owner has not " +
                "allowed yet (`github_owner_consent`).")
        }
    }
}

private fun ApiCatalog.cloudSessionReading() {
    get("/api/v1/cloud/sessions/{id}") {
        operationId = "getCloudSession"
        tag(TAG)
        summary = "A cloud session."
        description = "The session as it is now. $ORG_RESOLUTION"
        parameters {
            organizationHeader()
            pathId("id", SESSION_ID)
        }
        responses {
            ok<CloudSessionResponse>("The session.")
            notFound(NO_SESSION)
        }
    }
}

private fun ApiCatalog.cloudSessionStop() {
    delete("/api/v1/cloud/sessions/{id}") {
        operationId = "stopCloudSession"
        tag(TAG)
        summary = "Terminate a cloud session; its workspace is deleted."
        description = "The session goes to `terminated` once the reconciler stops it, and its workspace is " +
            "deleted. Once terminated, the waits its agent asked in it that are still open are cancelled. " +
            "$ORG_RESOLUTION"
        parameters {
            onBehalfHeader()
            organizationHeader()
            pathId("id", SESSION_ID)
        }
        responses {
            ok<CloudSessionResponse>("Accepted, the reconciler stops it.", HttpStatusCode.Accepted)
            notFound(NO_SESSION)
            conflict(STATE_CHANGE)
        }
    }
}

private fun ApiCatalog.cloudSessionPause() {
    post("/api/v1/cloud/sessions/{id}/pause") {
        operationId = "pauseCloudSession"
        tag(TAG)
        summary = "Pause a cloud session; the workspace is kept."
        description = "The agent stops and its pod is gone; the workspace is kept until the session expires or is " +
            "resumed. $ORG_RESOLUTION"
        parameters {
            onBehalfHeader()
            organizationHeader()
            pathId("id", SESSION_ID)
        }
        responses {
            ok<CloudSessionResponse>("Accepted, the reconciler pauses it.", HttpStatusCode.Accepted)
            notFound(NO_SESSION)
            conflict(STATE_CHANGE)
        }
    }
}

private fun ApiCatalog.cloudSessionResume() {
    post("/api/v1/cloud/sessions/{id}/resume") {
        operationId = "resumeCloudSession"
        tag(TAG)
        summary = "Resume a paused cloud session, within the plan's running cap and hours."
        description = "The reconciler starts a new execution on the kept workspace. A session an accepted transfer " +
            "is moving only resumes through that transfer. $ORG_RESOLUTION"
        parameters {
            onBehalfHeader()
            organizationHeader()
            pathId("id", SESSION_ID)
        }
        responses {
            ok<CloudSessionResponse>("Accepted, the reconciler starts a new execution.", HttpStatusCode.Accepted)
            error(HttpStatusCode.PaymentRequired, "The plan runs as many cloud sessions as it allows, or its " +
                "cloud hours of the period are used up (`payment_required`).")
            notFound(NO_SESSION)
            conflict("A session that is not paused, one being transferred (`session_transferring`), or one that " +
                "changed meanwhile (`conflict`, `session_busy`).")
        }
    }
}

private fun ApiCatalog.cloudSessionHosting() {
    post("/api/v1/cloud/sessions/{id}/host") {
        operationId = "hostCloudSession"
        tag(TAG)
        summary = "Open the next execution of a remote session, from its device."
        description = "With `runners.cloud.remote.v1`. The owner's own sign-in, from the session's device only: " +
            "opens a new epoch with a pending execution and answers the organization, that epoch and the " +
            "session; the previous live execution ends as `replaced`. The host then calls the runner " +
            "channel with that epoch and the same sign-in, which the server accepts for the current epoch " +
            "only (409 `execution_fenced` for an older one). $ORG_RESOLUTION"
        parameters {
            organizationHeader()
            pathId("id", SESSION_ID)
        }
        responses {
            ok<CloudHostResponse>("The organization, the new epoch and the session.")
            notFound(NO_SESSION)
            conflict("A cloud session (`not_remote`), another device (`remote_host_mismatch`), a session whose " +
                "device was removed (`remote_host_removed`), or a pause in progress, to retry " +
                "(`session_pausing`).")
            error(HttpStatusCode.UnprocessableEntity, "A service key or a sign-in without a device " +
                "(`remote_requires_device`).")
        }
    }
}

private fun ApiCatalog.cloudSessionGitHubAccess() {
    put("/api/v1/cloud/sessions/{id}/github") {
        operationId = "setCloudSessionGithubAccess"
        tag(TAG)
        summary = "Change the session's GitHub access: `owner`, `shared` or `none`."
        description = "`runners.cloud.github.v1`. Only the owner, signed in (console, app) or with the CLI's " +
            "login, never a service key or an agent acting for them (403 `github_owner_only`); anyone else, " +
            "404. Turning access off waits for a pause (409 `session_not_paused`), because a running agent " +
            "keeps a token it already received until it expires; turning it on or sharing applies from the " +
            "runner's next token renewal. The body is at most 1 KiB. $ORG_RESOLUTION"
        parameters {
            onBehalfHeader(ownerOnly = true)
            organizationHeader()
            pathId("id", SESSION_ID)
        }
        body<SetCloudSessionGitHubAccessRequest>("The access: `owner`, `shared` or `none`.")
        responses {
            ok<CloudSessionResponse>("The session, with its new access.")
            error(HttpStatusCode.Forbidden, "Not the owner in person (`github_owner_only`).")
            notFound(NO_SESSION)
            conflict("Removing the access of a session that is not paused (`session_not_paused`).")
        }
    }
}
