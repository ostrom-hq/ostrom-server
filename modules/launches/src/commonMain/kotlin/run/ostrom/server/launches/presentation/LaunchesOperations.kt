// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.launches.presentation

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

private const val AUTH_TAG = "auth"
private const val SESSIONS_TAG = "sessions"
private const val RUNNERS_TAG = "runners"

private const val LAUNCH_ID = "The launch's id."

private const val NOT_A_LAUNCHER = "The credential is no person's, or the person is no member of the organization " +
    "the request acts in (`not_found`)."

private const val REFUSED = "The organization's policy refuses the agent or the model, or a billing member " +
    "launches (`forbidden`)."

private const val RATE_LIMITED = "Too many launches lately (`rate_limited`): nothing was done. A launch is " +
    "limited per person in an organization (60) and per organization (600), over a sliding hour. " +
    "`Retry-After` tells in seconds when to try again."

/**
 * The launches module's operations: the organization's policy on launches, the start and end of an agent's launch
 * and its limits, the CLI's report of missing hooks, and the launch a cloud session's runner opens. The bodies and the
 * answers are the module's own types. The `sessions` tag is the sessions module's.
 */
internal fun ApiCatalog.launchesOperations() {
    tag(AUTH_TAG, "Signing in, and the launches through which a person's agents act with tokens of their own.")
    policyOperations()
    createLaunchOperations()
    endLaunchOperations()
    limitsOperations()
    runnerOperations()
}

/** The policy on launches. */
private fun ApiCatalog.policyOperations() {
    get("/api/v1/policy/agents") {
        operationId = "checkAgentPolicy"
        tag(AUTH_TAG)
        summary = "May this developer launch this agent with this model."
        description = "Scope `sessions:write`, a person's credential only. The CLI reads the organization's " +
            "verdict before it starts an agent, and `createLaunch` checks it again. A developer reads their " +
            "own verdict; `dev` names another member by login, which only an owner or an admin may do (403 " +
            "`forbidden`; 404 `not_found` for a login that is no member of the organization). Every " +
            "well-formed agent name is allowed; a model must be well formed and allowed by the organization's " +
            "rules on models, and a billing member runs no agent. A refusal, a malformed name included, is " +
            "the answer (`allowed` false with its `reason`), never an error. A launch that names no model is " +
            "not checked against the policy's default."
        parameters {
            organizationHeader()
            queryText("agent", "The agent's name in Ostrom.")
            queryText("dev", "The developer the verdict is for, by login: the caller when absent or their own.")
            queryText("model", "The model.")
        }
        responses {
            ok<AgentPolicyResponse>("The verdict: whether the agent may start, why not, and what a rule of the " +
                "policy tells the developer.")
            error(HttpStatusCode.Forbidden, "Another developer's verdict asked by a member who is neither an " +
                "owner nor an admin, or a credential without `sessions:write` (`forbidden`).")
            notFound(NOT_A_LAUNCHER)
        }
    }
}

/** Starting a launch. */
private fun ApiCatalog.createLaunchOperations() {
    post("/api/v1/launches") {
        operationId = "createLaunch"
        tag(AUTH_TAG)
        summary = "Start an agent launch: returns an agent token scoped to the sessions of this launch."
        description = "Scope `sessions:write` (the CLI's device token, a browser session). A person's only: an " +
            "agent token never launches. The person and the organization come from the credential. The token " +
            "expires with the launch, after 24 hours, and the device token never enters the agent " +
            "environment. 400 for a malformed repository, agent, model or workflow id (`invalid_request`) or " +
            "label (`invalid_label`); 403 when the organization's policy refuses the agent or the model, or " +
            "the credential may not launch; 404 when the person is no member of an organization the request " +
            "can act in, or the workflow is not one of the organization's. The agent token acts for the " +
            "person who launched it on its launch's sessions, the workflow step it plays " +
            "(`workflow:session`), the memory (`memory:use`), the capacity advice of its own sessions " +
            "(`guidance`) and the artifacts (`artifacts:write`: publish and share as that person, never " +
            "change an access nor delete)."
        parameters {
            organizationHeader()
        }
        body<CreateLaunchRequest>("The repository, the agent's name, the model, the workflow the launch " +
            "plays and its labels; only the agent is required.")
        responses {
            ok<LaunchResponse>("The launch and its agent token, whose secret is sent this once.",
                HttpStatusCode.Created)
            error(HttpStatusCode.Forbidden, REFUSED)
            notFound("The person is no member of an organization the request can act in, or the workflow is " +
                "not one of the organization's (`not_found`).")
            error(HttpStatusCode.TooManyRequests, RATE_LIMITED)
        }
    }
}

/** Ending a launch, and the CLI's report of missing hooks. */
private fun ApiCatalog.endLaunchOperations() {
    delete("/api/v1/launches/{launchId}") {
        operationId = "endLaunch"
        tag(AUTH_TAG)
        summary = "End a launch, revoking its agent token."
        description = "The launch's own agent token only: `ostrom <agent>` calls it when the agent ends, and " +
            "the token is revoked at once instead of living until its expiry. Any other credential, another " +
            "launch's agent token included, gets 404, the same answer as an unknown launch. Once ended, the " +
            "token is refused with 401 everywhere, this operation included."
        parameters {
            pathId("launchId", LAUNCH_ID)
        }
        responses {
            empty("The launch is ended and its agent token revoked.")
            notFound("Not the caller's own launch, or no such launch (`not_found`).")
        }
    }
    post("/api/v1/launches/{launchId}/hooks-missing") {
        operationId = "reportHooksMissing"
        tag(AUTH_TAG)
        summary = "Report that none of Ostrom's hooks ran in the agent's session."
        description = "The launch's own agent token only, as for `endLaunch`: the CLI waited for the first of " +
            "Ostrom's hooks in the agent's session, saw none, and says so, with whether it stopped the " +
            "agent. The organization's audit log then holds the event `hooks.missing` (subject " +
            "`launch:<launchId>`, `meta` holding `agent`, `repo`, `waitedSeconds` and `stopped`), read by " +
            "`listAudit` with `event=hooks.missing`. Any other credential, another launch's agent token " +
            "included, gets 404, the same answer as an unknown launch, before `waitedSeconds` is checked; " +
            "a `waitedSeconds` outside 0 to 3600 is 400."
        parameters {
            pathId("launchId", LAUNCH_ID)
        }
        body<HooksMissingRequest>("How long the CLI waited for the first hook, in seconds from 0 to 3600, and " +
            "whether it stopped the agent.")
        responses {
            empty("The report is recorded.")
            badRequest("A `waitedSeconds` outside 0 to 3600 (`invalid_request`).")
            notFound("Not the caller's own launch, or no such launch (`not_found`).")
        }
    }
}

/** The limits of a launch. */
private fun ApiCatalog.limitsOperations() {
    get("/api/v1/launches/{launchId}/limits") {
        operationId = "getLaunchLimits"
        tag(SESSIONS_TAG)
        summary = "The limits of a launch and what it spent (`launches.limits.v1`)."
        description = "Scope `sessions:write`, a person's credential only (a service key or an agent token " +
            "reads none). An owner or an admin reads any launch of the organization, anyone else their " +
            "own launch. 404 for a launch of another organization, one of another person that the caller does " +
            "not administer, or one that does not exist. A launch that was never given limits answers zeros " +
            "(`budgetMicros` 0 and `maxConcurrent` 0 add no cap) and `paused: false`."
        parameters {
            organizationHeader()
            pathId("launchId", LAUNCH_ID)
        }
        responses {
            ok<LaunchLimitsBody>("The limits in force and what the launch spent so far.")
            notFound("No such launch for the caller (`not_found`).")
        }
    }
    put("/api/v1/launches/{launchId}/limits") {
        operationId = "setLaunchLimits"
        tag(SESSIONS_TAG)
        summary = "Set the limits of a launch (`launches.limits.v1`)."
        description = "TeamCode's `PUT /runs/{id}/policy`: an owner or an admin from a signed-in session sets " +
            "the lifetime budget, the concurrent calls and the pause of one launch; an agent token never " +
            "does (403), and a member who administers nothing gets 404, like an unknown launch. Zero adds " +
            "no extra cap, and a cap never grants more than the member's, the workflow's and the " +
            "organization's own limits. A cap may be set below what the launch already spent: that blocks " +
            "later calls and rewrites nothing. 400 `invalid_launch_limits` for a `budgetMicros` outside 0 to " +
            "100000000000000 or a `maxConcurrent` outside 0 to 32; 409 `launch_ended` for a launch that " +
            "ended or expired. The organization's audit log holds `launch.limits_updated` (subject " +
            "`launch:<launchId>`). Applied at the next model call of the launch, on every replica. Past a " +
            "limit the gateway refuses the call itself: 403 for a paused launch, 429 once the launch spent " +
            "its budget or what is left does not cover the call."
        parameters {
            organizationHeader()
            pathId("launchId", LAUNCH_ID)
        }
        body<LaunchLimitsBody>("The budget, the concurrent calls and the pause; `spentMicros` is ignored.")
        responses {
            ok<LaunchLimitsBody>("The limits now in force and what the launch spent so far.")
            badRequest("A `budgetMicros` or a `maxConcurrent` out of its bounds (`invalid_launch_limits`).")
            notFound("No such launch, or a caller who administers no launch (`not_found`).")
            conflict("The launch ended or expired (`launch_ended`).")
        }
    }
}

/** The launch of a cloud session, opened by its runner. */
private fun ApiCatalog.runnerOperations() {
    post("/api/v1/cloud/runner/launch") {
        operationId = "openRunnerLaunch"
        tag(RUNNERS_TAG)
        summary = "The runner of a cloud session opens its session's launch."
        description = "With the execution's runner token (`Authorization: Bearer ostrom_runner_...`), checked " +
            "before the body is read: never a member's credential. The launch is the session owner's, under " +
            "the organization's policy and launch limit, and the agent in the pod gets its agent token, " +
            "never the runner token. A shell session opens none (400). 401 for an unknown runner token, 409 " +
            "`execution_fenced` for a session or an epoch that is not the token's current execution, 403 " +
            "when the policy refuses the owner, 404 when the owner is no longer a member."
        body<RunnerLaunchRequest>("The cloud session the runner was started for, and the execution it was " +
            "started for.")
        responses {
            ok<LaunchResponse>("The launch and its agent token, whose secret is sent this once.",
                HttpStatusCode.Created)
            badRequest("A session id that is none, or a shell session, which runs no agent to launch " +
                "(`invalid_request`).")
            error(HttpStatusCode.Unauthorized, "No runner token, or one no current execution holds " +
                "(`unauthorized`).")
            error(HttpStatusCode.Forbidden, REFUSED)
            notFound("The session's owner is no longer a member of its organization (`not_found`).")
            conflict("The runner token is not the session's current execution (`execution_fenced`).")
            error(HttpStatusCode.TooManyRequests, RATE_LIMITED)
        }
    }
}
