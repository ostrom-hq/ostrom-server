// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// The routes and bodies of `ostrom-core/api/openapi.yaml` (tags `gates` and `approvals`). The bodies mirror
// `dev.ostrom.core.api.models` field for field, same JSON, until ostrom-core publishes them: then this file keeps the
// resources only and imports the models.

/** The gates of the caller's organization. */
@Serializable
@Resource("/api/v1/gates")
class GatesApi {
    /** One gate. */
    @Serializable
    @Resource("{id}")
    class Id(val parent: GatesApi = GatesApi(), val id: String) {
        /** Uses the yes once, after the action (contract `consumeGate`). */
        @Serializable
        @Resource("consume")
        class Consume(val parent: Id)

        /** Holds the yes for one action before it runs, so that two parallel actions never share it. */
        @Serializable
        @Resource("reserve")
        class Reserve(val parent: Id)

        /** Gives a held yes back when the action did not run. */
        @Serializable
        @Resource("release")
        class Release(val parent: Id)
    }
}

/** The approvals of the caller's organization. */
@Serializable
@Resource("/api/v1/approvals")
class ApprovalsApi(val status: String? = null, val mine: Boolean? = null, val limit: Int? = null) {
    /** The caller's yes queue (contract `getApprovalQueue`); a constant segment, matched before `{id}`. */
    @Serializable
    @Resource("queue")
    class Queue(val parent: ApprovalsApi = ApprovalsApi())

    /** One approval. */
    @Serializable
    @Resource("{id}")
    class Id(val parent: ApprovalsApi = ApprovalsApi(), val id: String) {
        /** Says yes or no. */
        @Serializable
        @Resource("answer")
        class Answer(val parent: Id)

        /** The approval as the console and the app show it (contract `getApprovalView`). */
        @Serializable
        @Resource("view")
        class View(val parent: Id)
    }
}

/**
 * A push (contract `GatePush`).
 *
 * @property remote The push URL.
 * @property refs The refs and their commits.
 * @property force True for a force-push.
 */
@Serializable
data class GatePush(val remote: String, val refs: List<GatePushRef>, val force: Boolean? = null)

/**
 * A pushed ref (contract `GatePushRef`).
 *
 * @property ref The destination ref.
 * @property sha The commit.
 */
@Serializable
data class GatePushRef(val ref: String, val sha: String)

/**
 * A pull request (contract `GatePullRequest`). On a request only [number] is read: the head and base come from GitHub.
 *
 * @property number The number.
 * @property sha The head commit.
 * @property owner The base branch it merges into.
 */
@Serializable
data class GatePullRequest(val number: String, val sha: String, val owner: String? = null)

/**
 * The CI context of a gate asked by the GitHub check (contract `GateCi`).
 *
 * @property sha The commit.
 * @property pr The pull request number.
 */
@Serializable
data class GateCi(val sha: String? = null, val pr: String? = null)

/**
 * What a hook or the MCP tool asks before a boundary action (contract `GateAskRequest`). `dev` is never read: the
 * asker is the credential's person (audit A2).
 *
 * @property sessionId The agent session.
 * @property runId The workflow run.
 * @property repo The repository the session declared.
 * @property realRepo The repository the command runs in.
 * @property cls The action class.
 * @property tool The agent tool.
 * @property command The command.
 * @property dev Ignored.
 * @property harnessVersion The launcher or plugin version.
 * @property ci The CI context.
 * @property diff The diff a push sends.
 * @property push The refs a push sends.
 * @property pr The pull request of a merge.
 */
@Serializable
data class GateAskRequest(
    val sessionId: String? = null,
    val runId: String? = null,
    val repo: String? = null,
    val realRepo: String? = null,
    @SerialName("class") val cls: String,
    val tool: String? = null,
    val command: String? = null,
    val dev: String? = null,
    val harnessVersion: String? = null,
    val ci: GateCi? = null,
    val diff: String? = null,
    val push: GatePush? = null,
    val pr: GatePullRequest? = null,
)

/**
 * The workflow gate that covers an action (contract `GateRef`).
 *
 * @property workflowId The workflow.
 * @property workflowName Its name.
 * @property nodeId The gate node.
 * @property label The gate's label.
 * @property approvers Who may say yes.
 * @property status The workflow's status.
 * @property enforce True when the gate blocks.
 * @property basis `declared` or `guessed`.
 * @property reason Where the approvers come from.
 */
@Serializable
data class GateRef(
    val workflowId: String,
    val workflowName: String,
    val nodeId: String,
    val label: String,
    val approvers: List<String>,
    val status: String,
    val enforce: Boolean,
    val basis: String? = null,
    val reason: String,
)

/**
 * A gate request (contract `GateRequest`).
 *
 * @property id The gate id.
 * @property approvalId The caller's approval, when they are an approver.
 * @property sessionId The first session that asked.
 * @property runId The run that asked.
 * @property repo The repository.
 * @property realRepo The repository the command runs in, when it differs.
 * @property dev The person who asked first.
 * @property cls The action class.
 * @property label The action's label.
 * @property tool The agent tool.
 * @property command The command.
 * @property key The gate key (a digest).
 * @property gate The workflow gate.
 * @property mode `enforced`, `shadow` or `ungated`.
 * @property status `pending`, `approved`, `rejected`, `shadow`, `ungated` or `consumed` (a reserved yes reads
 *   `approved`).
 * @property at When it was asked.
 * @property decidedBy Who answered.
 * @property decidedAt When.
 * @property receiptId The signed receipt of the decision.
 * @property harnessVersion The launcher or plugin version.
 * @property ci The CI context.
 * @property devVerified True: the asker is always the credential's person.
 * @property pr The pull request, head and base read from GitHub.
 * @property askedVia The paths that asked (`hook`, `ci`, `mcp`).
 * @property selfApproved True when the only approver, who asked, said yes.
 * @property push The push.
 */
@Serializable
data class GateRequest(
    val id: String,
    val approvalId: String? = null,
    val sessionId: String? = null,
    val runId: String? = null,
    val repo: String? = null,
    val realRepo: String? = null,
    val dev: String,
    @SerialName("class") val cls: String,
    val label: String,
    val tool: String,
    val command: String,
    val key: String,
    val gate: GateRef?,
    val mode: String,
    val status: String,
    val at: String,
    val decidedBy: String? = null,
    val decidedAt: String? = null,
    val receiptId: String? = null,
    val harnessVersion: String? = null,
    val ci: GateCi? = null,
    val devVerified: Boolean? = null,
    val pr: GatePullRequest? = null,
    val askedVia: List<String>? = null,
    val selfApproved: Boolean? = null,
    val push: GatePush? = null,
)

/**
 * A line shown to the approvers (contract `Evidence`).
 *
 * @property label The label.
 * @property value The value.
 * @property url A link.
 */
@Serializable
data class Evidence(val label: String, val value: String, val url: String? = null)

/**
 * One approver's request (contract `Approval`).
 *
 * @property id The approval id.
 * @property group The gate it belongs to.
 * @property workflowId The workflow.
 * @property title What is asked.
 * @property evidence What is shown.
 * @property repo The repository the yes is bound to.
 * @property sha The commit the yes is bound to.
 * @property commits The commits the yes covers.
 * @property requestedFrom The approver.
 * @property channel Where it was answered, `console` until then.
 * @property status `pending`, `approved`, `rejected`, or `expired` once another approver answered.
 * @property decidedBy Who answered the gate.
 * @property decidedAt When.
 * @property shownDigest What an answer must name.
 * @property askedBy Who asked; none of them answers unless they are the only approver.
 * @property selfApproved True when the yes came from the asker.
 */
@Serializable
data class Approval(
    val id: String,
    val group: String? = null,
    val workflowId: String? = null,
    val title: String,
    val evidence: List<Evidence> = emptyList(),
    val repo: String? = null,
    val sha: String? = null,
    val commits: List<String>? = null,
    val requestedFrom: String,
    val channel: String,
    val status: String,
    val decidedBy: String? = null,
    val decidedAt: String? = null,
    val shownDigest: String,
    val askedBy: List<String>? = null,
    val selfApproved: Boolean? = null,
)

/**
 * An answer (contract `AnswerApprovalRequest`).
 *
 * @property answer `approve` or `reject`.
 * @property shownDigest The digest of what the approver was shown.
 * @property comment A note.
 */
@Serializable
data class AnswerApprovalRequest(val answer: ApprovalAnswer, val shownDigest: String, val comment: String? = null)

/** Yes or no (contract `ApprovalAnswer`). */
@Serializable
enum class ApprovalAnswer {
    /** Yes. */
    @SerialName("approve")
    APPROVE,

    /** No. */
    @SerialName("reject")
    REJECT,
}

/**
 * A gate and what waits on it (contract `GateResponse`).
 *
 * @property gate The gate.
 * @property approval The caller's approval, when they are an approver.
 * @property receipt The signed receipt (filled by the receipts module).
 * @property waitingFor Who can still say yes.
 */
@Serializable
data class GateResponse(
    val gate: GateRequest,
    val approval: Approval? = null,
    val receipt: JsonElement? = null,
    val waitingFor: List<String>? = null,
)

/**
 * The answer to an answer (contract `answerApproval`).
 *
 * @property approval The approval, answered.
 * @property receipt The signed receipt (filled by the receipts module).
 */
@Serializable
data class AnswerApprovalResponse(val approval: Approval, val receipt: JsonElement? = null)

/**
 * A page of approvals (contract `listApprovals`).
 *
 * @property items The approvals, newest first.
 * @property next The cursor of the next page; none yet.
 */
@Serializable
data class ApprovalsPage(val items: List<Approval>, val next: String? = null)
