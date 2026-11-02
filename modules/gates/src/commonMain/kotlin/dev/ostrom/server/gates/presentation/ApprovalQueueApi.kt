// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.presentation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// The bodies of `getApprovalQueue` and `getApprovalView` (`ostrom-core/api/openapi.yaml`, tag `approvals`). Only the
// fields the server knows are declared: the contract's optional `rules`, `diff`, `note`, `receipt`, `verification`,
// `who.agent`, `who.model` and `you.name` are left absent rather than invented. `receipt` is filled in the answer.

/**
 * The yes queue of the caller (contract `ApprovalQueue`).
 *
 * @property operator The login a yes is signed as.
 * @property you Who is looking, and from where.
 * @property pending Their approvals still waiting, newest first.
 * @property recent Their last answered or closed approvals, newest first.
 */
@Serializable
data class ApprovalQueue(
    val operator: String,
    val you: ApprovalViewer,
    val pending: List<ApprovalViewResponse>,
    val recent: List<ApprovalViewResponse>,
)

/**
 * Who is looking at the yes queue (contract `ApprovalViewer`).
 *
 * @property login Their login.
 * @property via `console`, `app` or `cli`.
 */
@Serializable
data class ApprovalViewer(val login: String, val via: String)

/**
 * An approval as the console and the app show it (contract `ApprovalView`; the domain's `ApprovalView` is the request
 * and its gate).
 *
 * @property approval The approval, as `GET /approvals/{id}` serves it.
 * @property who Who asks.
 * @property what What is asked.
 * @property gate The workflow gate that covers the action.
 * @property pr The pull request of a merge, head and base read from GitHub.
 * @property askedVia The paths that asked (`hook`, `ci`, `mcp`).
 * @property you What the caller may do with it.
 * @property receipt The signed receipt of the decision, in the answer to a yes or a no.
 */
@Serializable
data class ApprovalViewResponse(
    val approval: Approval,
    val who: ApprovalWho,
    val what: ApprovalWhat,
    val gate: GateRef? = null,
    val pr: GatePullRequest? = null,
    val askedVia: List<String>,
    val you: ApprovalCallerView,
    val receipt: JsonElement? = null,
)

/**
 * Who asks (contract `ApprovalView.who`).
 *
 * @property dev The person who asked first.
 * @property session The first agent session that asked.
 */
@Serializable
data class ApprovalWho(val dev: String? = null, val session: String? = null)

/**
 * What is asked (contract `ApprovalView.what`).
 *
 * @property action The action's label.
 * @property command The command, when a hook sent one.
 * @property repo The repository the command runs in.
 * @property sessionRepo The repository of the session, when it differs.
 * @property harness The launcher or plugin version.
 */
@Serializable
data class ApprovalWhat(
    val action: String,
    val command: String? = null,
    val repo: String? = null,
    val sessionRepo: String? = null,
    val harness: String? = null,
)

/**
 * What the caller may do with an approval (contract `ApprovalCallerView`).
 *
 * @property login The login a yes is signed as.
 * @property self True when the caller asked for the action.
 * @property canApprove True when their yes would pass now (a stale sign-in aside: the answer route asks for it).
 * @property reason Why not, in plain words.
 * @property selfApproval True when their yes would pass as a self-approval (they asked, and are the only approver).
 */
@Serializable
data class ApprovalCallerView(
    val login: String,
    val self: Boolean,
    val canApprove: Boolean,
    val reason: String? = null,
    val selfApproval: Boolean? = null,
)
