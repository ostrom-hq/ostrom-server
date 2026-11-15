// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Parameters
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

/** The tag of every operation of the runners module. */
internal const val TAG = "runners"

/** The organization's resolution, which every member's call goes through. */
internal const val ORG_RESOLUTION = "404 and 409 are the organization's resolution (`Ostrom-Org`)."

/** The answer for a session that is none of the caller's. */
internal const val NO_SESSION = "No such cloud session in the caller's organization, or one the caller may not " +
    "use, or a malformed id (`not_found`). The caller is also no member of an organization the request can act in."

/** The credential of the runner channel. */
internal const val RUNNER_TOKEN = "Authenticated by the execution's runner token (`Authorization: Bearer " +
    "ostrom_runner_...`), checked before the body is read; never a member's credential."

/** The answer for a runner call of an execution that is not the current one. */
internal const val FENCED = "The runner token is not the session's current execution (`execution_fenced`)."

/** The answer for a runner call without a known runner token. */
internal const val RUNNER_UNKNOWN = "No runner token, or one no execution holds (`unauthorized`)."

/** The answer for a body past the bound of its route. */
internal const val TOO_LARGE = "A body over the bound of this route (`payload_too_large`)."

/** The session id of a path. */
internal const val SESSION_ID = "The cloud session's id."

/**
 * `Ostrom-On-Behalf-Of` of a cloud session write (`runners.cloud.onbehalf.v1`, TeamCode's `TeamCode-On-Behalf-Of`).
 */
internal fun Parameters.Builder.onBehalfHeader(ownerOnly: Boolean = false, writes: Boolean = false) = headerText(
    "Ostrom-On-Behalf-Of",
    when {
        ownerOnly -> "`runners.cloud.onbehalf.v1`: on this owner-only write, absent or naming the caller; any other " +
            "member, even for an administrator, is the route's owner-only 403."
        writes -> "`runners.cloud.onbehalf.writes.v1`, as on the session writes: $ON_BEHALF"
        else -> ON_BEHALF
    },
)

/** The refusals of `Ostrom-On-Behalf-Of` on a write that reads it with `runners.cloud.onbehalf.writes.v1`. */
internal const val ON_BEHALF_FORBIDDEN = "A caller who may not act for someone else named another member in " +
    "`Ostrom-On-Behalf-Of` (`on_behalf_forbidden`)."

/** The member `Ostrom-On-Behalf-Of` names is no active member. */
internal const val ON_BEHALF_INVALID_MEMBER = "The member `Ostrom-On-Behalf-Of` names is not an active member of the " +
    "organization (`invalid_effective_member`)."

private const val ON_BEHALF =
    "`runners.cloud.onbehalf.v1`: the member the write acts for, by member id or login (an id wins over a " +
        "login that spells it), 1 to 64 characters of `A-Za-z0-9._-`. Only an owner or an admin signed in, or " +
        "an administrator's service key with `cloud:admin`, may name another member; on any other credential it " +
        "must be absent or name the caller (403 `on_behalf_forbidden`). The member must be an active member of " +
        "the organization (422 `invalid_effective_member`); several values or a malformed one are 400 " +
        "`invalid_on_behalf`. It grants no right of its own (TeamCode's `actor()`): on a creation the member " +
        "becomes the owner; on any other write the session or transfer must be the caller's own, whatever it " +
        "names (404 otherwise, an administrator's role opens nobody else's), and the member is only journaled as " +
        "the effective one."

/**
 * The runners module's operations: the cloud agent sessions and their conversation, the ownership transfers, the
 * terminal, the registered runners, and the channel the runner of an execution calls. The bodies are the module's own
 * mirrors of ostrom-core's `CloudSession*`, `CloudTransfer*`, `Runner*` and `Cloud*` models.
 */
internal fun ApiCatalog.runnersOperations() {
    tag(TAG, "Cloud agent sessions, their conversation and terminal, the ownership transfers, and the runners that " +
        "run them.")
    cloudSessionOperations()
    cloudTransferOperations()
    cloudTerminalOperations()
    cloudConversationOperations()
    cloudFileOperations()
    deviceDaemonOperations()
    registeredRunnerOperations()
    runnerChannelOperations()
    runnerConversationOperations()
}

/** Registering, listing and removing the organization's runners. */
private fun ApiCatalog.registeredRunnerOperations() {
    registeredRunnerListing()
    registeredRunnerRegistration()
    registeredRunnerRemoval()
    registeredRunnerHeartbeat()
}

private fun ApiCatalog.registeredRunnerListing() {
    get("/api/v1/runners") {
        operationId = "listRunners"
        tag(TAG)
        summary = "The organization's runners."
        description = "The organization's registered runners, by name, then the runners at work for the caller's " +
            "cloud sessions (one per execution). Any member, or a credential with `cloud:read`. A runner is " +
            "`online` when it gave a sign of life within the last 90 seconds, `offline` otherwise. A " +
            "registered runner's `token` is never listed. $ORG_RESOLUTION"
        parameters {
            organizationHeader()
        }
        responses {
            ok<RunnersPage>("The registered runners, then the runners at work.")
            notFound("The caller is no member of an organization the request can act in (`not_found`).")
        }
    }
}

private fun ApiCatalog.registeredRunnerRegistration() {
    post("/api/v1/runners") {
        operationId = "registerRunner"
        tag(TAG)
        summary = "Register a runner."
        description = "An owner or an admin of the organization, from a signed-in console session (a personal, CLI " +
            "or service token gets 403), registers a machine. The role is checked before the body is read. " +
            "The answer carries the runner's `token`, shown this once: only its hash is kept. The name is " +
            "unique in the organization whatever its case, and an organization registers at most 100 " +
            "runners (409 otherwise). The name is 1 to 64 characters and refuses control and invisible " +
            "formatting characters; at most 16 labels, each 1 to 64 letters, digits, `.`, `_`, `:` or `-`, " +
            "starting with a letter or a digit (400 otherwise). The answer is not cached " +
            "(`Cache-Control: no-store`), and the registration is written to the organization's audit log " +
            "(`runner.registered`), never with the token. $ORG_RESOLUTION"
        parameters {
            organizationHeader()
        }
        body<RegisterRunnerRequest>("The runner's name, labels and version.")
        responses {
            ok<RegisteredRunnerDto>("The runner, with its token, this once.", HttpStatusCode.Created)
            badRequest("A name or a label that does not fit the rules above (`invalid_request`).")
            notFound("The caller is no member of an organization the request can act in (`not_found`).")
            conflict("The name is taken, whatever its case, or the organization registered 100 runners " +
                "(`conflict`).")
        }
    }
}

private fun ApiCatalog.registeredRunnerRemoval() {
    delete("/api/v1/runners/{id}") {
        operationId = "deleteRunner"
        tag(TAG)
        summary = "Remove a runner."
        description = "An owner or an admin, from a signed-in console session, removes a registered runner and its " +
            "token. A runner of another organization, or a malformed id, is not found (404). The removal is " +
            "written to the organization's audit log (`runner.deleted`). $ORG_RESOLUTION"
        parameters {
            organizationHeader()
            pathId("id", "The registered runner's id.")
        }
        responses {
            empty("The runner and its token are gone.")
            notFound("No such registered runner in the organization, or a malformed id (`not_found`).")
        }
    }
}

private fun ApiCatalog.registeredRunnerHeartbeat() {
    post("/api/v1/runners/{id}/heartbeat") {
        operationId = "runnerHeartbeat"
        tag(TAG)
        summary = "A registered runner's sign of life; returns the work assigned to it."
        description = "Authenticated by the runner's own key (`Authorization: Bearer ostrom_runner_...`, from " +
            "`registerRunner`'s answer), not a member's credential. Marks the runner seen: `listRunners` " +
            "shows it `online` for 90 seconds after its last heartbeat. An unknown runner, a malformed id, " +
            "another runner's key or a runner removed meanwhile all answer 401 alike. A runner that runs " +
            "cloud sessions sends a JSON body: the executions its machine has and the workspaces it holds. " +
            "The answer is what it must hold: the executions to run, each handed over once with its plan " +
            "(the volumes and containers to create, the last one holding the execution's runner token), and " +
            "the workspaces to keep. Anything else of its own, it stops and deletes. A runner that sends no " +
            "JSON body is given nothing; an unknown execution state, a malformed session id or a capacity " +
            "outside 0 to 256 answers 400. `sessions` is kept for older runners and stays empty. The answer " +
            "is not cached (`Cache-Control: no-store`)."
        parameters {
            pathId("id", "The registered runner's id.")
            queryNumber(
                "wait",
                "Seconds the heartbeat may wait for an execution to hand over, for a runner that has room; it " +
                    "answers as soon as one is taken or assigned to it, and at the end of the wait otherwise. " +
                    "Absent or 0 answers at once. A value outside 0 to 25 is read as the nearest bound.",
            )
        }
        body<RegisteredRunnerHeartbeatRequest>(
            "The executions the machine has, the workspaces it holds and its capacity; leave the body out to " +
                "report nothing.",
            required = false,
        )
        responses {
            ok<RegisteredRunnerHeartbeatResponse>("The executions to run, with their plans, and the workspaces to " +
                "keep.")
            badRequest("An unknown execution state, a malformed session id or a capacity outside 0 to 256 " +
                "(`invalid_request`).")
            error(HttpStatusCode.Unauthorized, RUNNER_UNKNOWN)
        }
    }
}
