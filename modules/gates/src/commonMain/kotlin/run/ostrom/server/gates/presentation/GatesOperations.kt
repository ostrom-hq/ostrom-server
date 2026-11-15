// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates.presentation

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
import run.ostrom.server.kernel.http.docs.queryFlag
import run.ostrom.server.kernel.http.docs.queryLimit
import run.ostrom.server.kernel.http.docs.queryNumber
import run.ostrom.server.kernel.http.docs.queryText
import run.ostrom.server.kernel.http.docs.rateLimited
import io.ktor.http.HttpStatusCode

private const val GATES_TAG = "gates"
private const val APPROVALS_TAG = "approvals"

private const val APPROVAL_NOT_FOUND = "No such approval in the caller's organization (`not_found`)."

private const val BAD_ID = "A malformed id (`invalid_request`)."

private const val BAD_PAGE = "An unknown status or cursor (`invalid_request`)."

private const val GATE_NOT_FOUND = "No such gate in the caller's organization (`not_found`)."

private const val NOT_USABLE = "The yes cannot be used that way: it was not given, is used up, or another " +
    "session holds it (`conflict`)."

/**
 * The gates module's operations: the gates a boundary action asks, the yes it serves once, and the approvals
 * their approvers answer. The bodies are the module's own mirrors of ostrom-core's `Gate*` and `Approval*` models.
 */
internal fun ApiCatalog.gatesOperations() {
    tag(GATES_TAG, "The gates of boundary actions: asking one, and using the yes it is given.")
    tag(APPROVALS_TAG, "The approvals of gates: what an approver sees, and their yes or no.")
    gateOperations()
    gateUseOperations()
    localDenialOperations()
    approvalOperations()
    approvalReadOperations()
    answerOperations()
}

/** Asking a gate and reading it. */
private fun ApiCatalog.gateOperations() {
    post("/api/v1/gates") {
        operationId = "askGate"
        tag(GATES_TAG)
        summary = "Ask the gate of a boundary action (hook PreToolUse, CI, MCP)."
        description = "409 `conflict` (its message starting `session closed:`) in a closed session. 409 " +
            "`check_not_attested` when the gate is " +
            "enforced and its rule needs a check attested (RuleGateCheck.attested) whose last run is not proven " +
            "by an Ostrom runner (isolated_runner) or by the CI on the commit the gate covers (ci); the message " +
            "names each such check and why (declared by the workstation, failed, or not run). No approver is " +
            "asked until the check is proven."
        parameters {
            organizationHeader()
        }
        body<GateAskRequest>("The action to gate: its class, its command and what it touches.")
        responses {
            ok<GateResponse>("The gate, with its approval and, once answered, its receipt.")
            badRequest("A field out of bounds, a push that redirects git (`invalid_request`), or a launcher " +
                "too old to say which commits it sends (`invalid_request`).")
            conflict("The session is closed (message starting `session closed:`), the gate has no approver " +
                "(`conflict`), or a check it needs is not proven by a runner or the CI (`check_not_attested`).")
            error(HttpStatusCode.ServiceUnavailable, "A merge whose pull request the GitHub integration " +
                "cannot read (`unavailable`).")
        }
    }
    get("/api/v1/gates/{id}") {
        operationId = "getGate"
        tag(GATES_TAG)
        summary = "A gate request; `wait` long-polls up to that many seconds for an answer."
        description = "With `wait`, a pending gate is waited on for its answer: the response comes as soon " +
            "as the gate is no longer pending, or with it still pending when the wait ends; the caller then " +
            "asks again. A gate that is not pending answers at once, and so does a gate of another organization " +
            "(404, as if it did not exist) or of another launch of the caller's organization (403). The server " +
            "caps the wait at 30 seconds and reads a value outside 0 to 30 as the nearest bound. A waiting " +
            "request holds no database connection. A client sets its HTTP timeout above the wait."
        parameters {
            organizationHeader()
            pathId("id", "The gate's id.")
            queryNumber("wait", "Seconds to wait on a pending gate for its answer; absent or 0 reads at " +
                "once.")
        }
        responses {
            ok<GateResponse>("The gate; still pending when the wait ended first.")
            badRequest(BAD_ID)
            notFound(GATE_NOT_FOUND)
            rateLimited()
        }
    }
}

/** The yes of a gate: held, used and given back. */
private fun ApiCatalog.gateUseOperations() {
    post("/api/v1/gates/{id}/reserve") {
        operationId = "reserveGate"
        tag(GATES_TAG)
        summary = "Hold a yes for one action before it runs, so that two parallel actions never share it."
        parameters {
            organizationHeader()
            pathId("id", "The gate's id.")
        }
        responses {
            ok<GateResponse>("The gate, its yes held.")
            badRequest(BAD_ID)
            notFound(GATE_NOT_FOUND)
            conflict(NOT_USABLE)
        }
    }
    post("/api/v1/gates/{id}/consume") {
        operationId = "consumeGate"
        tag(GATES_TAG)
        summary = "Consume a yes: it serves the action once."
        parameters {
            organizationHeader()
            pathId("id", "The gate's id.")
        }
        responses {
            ok<GateResponse>("The gate, its yes used.")
            badRequest(BAD_ID)
            notFound(GATE_NOT_FOUND)
            conflict(NOT_USABLE)
        }
    }
    post("/api/v1/gates/{id}/release") {
        operationId = "releaseGate"
        tag(GATES_TAG)
        summary = "Give a held yes back when the action did not run."
        parameters {
            organizationHeader()
            pathId("id", "The gate's id.")
        }
        responses {
            ok<GateResponse>("The gate, its yes given back.")
            badRequest(BAD_ID)
            notFound(GATE_NOT_FOUND)
            conflict(NOT_USABLE)
        }
    }
}

/** The refusal the hook made alone. */
private fun ApiCatalog.localDenialOperations() {
    post("/api/v1/gates/local-denials") {
        operationId = "reportLocalGateDenial"
        tag(GATES_TAG)
        summary = "Record that the hook refused a gated action on the workstation without asking the server " +
            "(audit A6)."
        description = "The hook refuses an action locally when it cannot be asked honestly " +
            "(`GateLocalDenial.reason`): a push that redirects git (`--git-dir`, another checkout, `GIT_DIR`), " +
            "a push Ostrom cannot read, and the other refusals the hook makes alone. The refusal stands " +
            "whatever this call answers: the hook sends it after refusing, never waits on it, and an " +
            "unreachable server changes nothing. The server writes the audit line `gate.denied_locally` in the " +
            "organization's chain (meta: session, class, reason, the command as normalized with its secrets " +
            "removed, repo)."
        parameters {
            organizationHeader()
        }
        body<GateLocalDenial>("The session, the class, the reason and the command the hook refused.")
        responses {
            empty("The refusal is recorded in the audit log.", HttpStatusCode.Accepted)
        }
    }
}

/** Listing approvals and the queue of the caller. */
private fun ApiCatalog.approvalOperations() {
    get("/api/v1/approvals") {
        operationId = "listApprovals"
        tag(APPROVALS_TAG)
        summary = "Approvals, most recent first; `mine` lists those waiting for the caller."
        parameters {
            organizationHeader()
            queryText("status", "One status: `pending`, `approved`, `rejected` or `expired` (closed unanswered); " +
                "every status when absent.")
            queryFlag("mine", "Only the approvals waiting for the caller.")
            queryText("before", "The `next` of the previous page.")
            queryLimit("The page size (50 by default).", 100)
        }
        responses {
            ok<ApprovalsPage>("A page of approvals, `next` set when another may follow.")
            badRequest(BAD_PAGE)
            rateLimited()
        }
    }
    get("/api/v1/approvals/queue") {
        operationId = "getApprovalQueue"
        tag(APPROVALS_TAG)
        summary = "The yes queue of the caller: pending approvals and recent answers, as the console and " +
            "the app show them."
        parameters {
            organizationHeader()
        }
        responses {
            ok<ApprovalQueue>("The caller's pending approvals and recent answers.")
        }
    }
}

/** Reading one approval, as it is and as the console and the app show it. */
private fun ApiCatalog.approvalReadOperations() {
    get("/api/v1/approvals/{id}") {
        operationId = "getApproval"
        tag(APPROVALS_TAG)
        summary = "An approval, with what it shows."
        parameters {
            organizationHeader()
            pathId("id", "The approval's id.")
        }
        responses {
            ok<Approval>("The approval.")
            badRequest(BAD_ID)
            notFound(APPROVAL_NOT_FOUND)
        }
    }
    get("/api/v1/approvals/{id}/view") {
        operationId = "getApprovalView"
        tag(APPROVALS_TAG)
        summary = "An approval as the console and the app show it."
        parameters {
            organizationHeader()
            pathId("id", "The approval's id.")
        }
        responses {
            ok<ApprovalViewResponse>("The approval, who asked and what for, and what the caller may do.")
            badRequest(BAD_ID)
            notFound(APPROVAL_NOT_FOUND)
        }
    }
}

/** Answering an approval. */
private fun ApiCatalog.answerOperations() {
    post("/api/v1/approvals/{id}/answer") {
        operationId = "answerApproval"
        tag(APPROVALS_TAG)
        summary = "Say yes or no, as the caller."
        description = "409 shown_digest_mismatch when the shown digest differs, " +
            "409 approval_closed when already answered (a signed answer never changes); 403 " +
            "self_approval_refused when the asker answers while another approver exists; 403 " +
            "github_link_required when the gate's policy is never-self-approve, the pull request's author " +
            "linked no Ostrom person and the answerer linked no GitHub account (the gate waits, Q-GATES-G1); " +
            "403 step_up_required when a yes comes from a sign-in more than 5 minutes old, or from the phone " +
            "app not unlocked in the last 5 minutes (`unlockMobileApp`), or from the phone app, to a " +
            "high-risk action, without an unlock bound to this request less than 60 seconds old and not used " +
            "yet (`unlockMobileApp.requestId`, `error.requestBound` true); the 200 is the `ApprovalView` of the " +
            "answered approval, with its signed `receipt`, a no's included."
        parameters {
            organizationHeader()
            pathId("id", "The approval's id.")
        }
        body<AnswerApprovalRequest>("The answer, the digest of what the approver was shown, and an optional " +
            "comment.")
        responses {
            ok<ApprovalViewResponse>("The answered approval, with its signed receipt, a no included.")
            badRequest(BAD_ID)
            error(HttpStatusCode.Forbidden, "Only an approver who is a person answers, and not their own " +
                "request while another approver exists (`self_approval_refused`), without a linked GitHub " +
                "account where the gate requires one (`github_link_required`), nor with a sign-in older than 5 " +
                "minutes (`step_up_required`); anyone else is refused (`forbidden`).")
            notFound(APPROVAL_NOT_FOUND)
            conflict("What the gate covers changed since it was shown (`shown_digest_mismatch`), or it was " +
                "already answered (`approval_closed`).")
        }
    }
}
