package dev.ostrom.server.gates.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The gates of the caller's organization. Moves to ostrom-core's contract once it is published. */
@Serializable
@Resource("/v1/gates")
class GatesApi {
    /** One gate. */
    @Serializable
    @Resource("{id}")
    class Id(val parent: GatesApi = GatesApi(), val id: String) {
        /** Reserve, consume or release its yes (`reserve`, `consume`, `release`). */
        @Serializable
        @Resource("{use}")
        class Use(val parent: Id, val use: String)
    }
}

/** The approval requests sent to the caller. */
@Serializable
@Resource("/v1/approvals")
class ApprovalsApi(val limit: Int = 50) {
    /** One request. */
    @Serializable
    @Resource("{id}/answer")
    class Answer(val parent: ApprovalsApi = ApprovalsApi(), val id: String)
}

/**
 * Asks for a gate before a boundary action (the hook's PreToolUse, the MCP tool).
 *
 * @property sessionId The agent session.
 * @property repo The repository the session declared.
 * @property realRepo The repository the command runs in, read by the hook.
 * @property actionClass The action class the hook matched.
 * @property command The command.
 * @property push The refs a push sends.
 * @property pullRequest The pull request number of a merge (the head and base are read from GitHub, never sent).
 * @property commits The commits a push sends, oldest first.
 * @property diff The diff a push sends, shown to the approvers.
 * @property redirected True when the classifier saw a redirect of git (`GIT_DIR`, `-c remote.*`…).
 */
@Serializable
data class OpenGateRequest(
    val sessionId: String? = null,
    val repo: String? = null,
    val realRepo: String? = null,
    val actionClass: String,
    val command: String = "",
    val push: PushDto? = null,
    val pullRequest: Int? = null,
    val commits: List<String> = emptyList(),
    val diff: String? = null,
    val redirected: Boolean = false,
)

/**
 * A push.
 *
 * @property remote The push URL.
 * @property refs The refs and their commits.
 * @property force True for a force-push.
 */
@Serializable
data class PushDto(val remote: String, val refs: List<PushRefDto>, val force: Boolean = false)

/**
 * A pushed ref.
 *
 * @property ref The destination ref.
 * @property sha The commit.
 */
@Serializable
data class PushRefDto(val ref: String, val sha: String)

/**
 * An answer.
 *
 * @property answer `yes` or `no`.
 * @property shownDigest The digest of what the approver was shown (from the gate they read).
 * @property channel Where the answer was given.
 */
@Serializable
data class AnswerRequest(
    val answer: AnswerDto,
    val shownDigest: String,
    val channel: ChannelDto = ChannelDto.CONSOLE,
)

/** Yes or no. */
@Serializable
enum class AnswerDto {
    @SerialName("yes") YES,
    @SerialName("no") NO,
}

/** Where an answer was given. */
@Serializable
enum class ChannelDto {
    @SerialName("console") CONSOLE,
    @SerialName("mobile") MOBILE,
    @SerialName("slack") SLACK,
    @SerialName("email") EMAIL,
}

/**
 * A gate, as the hook, the console and the yes page read it.
 *
 * @property id The gate id.
 * @property label The action.
 * @property mode `enforced`, `shadow` or `ungated`.
 * @property status Where the gate is (lower-case [dev.ostrom.server.gates.domain.models.GateStatus]).
 * @property repo The repository.
 * @property actionClass The action class.
 * @property commits The commits the yes covers.
 * @property pullRequest The pull request (`#6@<sha>-><base>`), when it is a merge.
 * @property askedBy Who asked.
 * @property askedVia The paths that asked.
 * @property waitingFor Who can still say yes.
 * @property shownDigest What an answer must name.
 * @property evidence What the approvers are shown.
 * @property requests One per approver.
 * @property decidedBy Who answered.
 * @property decidedAt When.
 * @property selfApproved True when the only approver, who asked, said yes.
 * @property receiptId The signed receipt of the decision.
 * @property createdAt When it was opened.
 */
@Serializable
data class GateResponse(
    val id: String,
    val label: String,
    val mode: String,
    val status: String,
    val repo: String?,
    val actionClass: String,
    val commits: List<String>,
    val pullRequest: String?,
    val askedBy: List<String>,
    val askedVia: List<String>,
    val waitingFor: List<String>,
    val shownDigest: String,
    val evidence: List<EvidenceDto>,
    val requests: List<ApprovalRequestResponse>,
    val decidedBy: String?,
    val decidedAt: String?,
    val selfApproved: Boolean,
    val receiptId: String?,
    val createdAt: String,
)

/**
 * A line shown to the approvers.
 *
 * @property label The label.
 * @property value The value.
 */
@Serializable
data class EvidenceDto(val label: String, val value: String)

/**
 * An approval request.
 *
 * @property id The request id (what an answer names).
 * @property gateId The gate.
 * @property approver The approver.
 * @property status `pending`, `approved`, `rejected` or `closed`.
 * @property answeredAt When it was answered or closed.
 * @property channel Where it was answered.
 */
@Serializable
data class ApprovalRequestResponse(
    val id: String,
    val gateId: String,
    val approver: String,
    val status: String,
    val answeredAt: String?,
    val channel: String?,
)

/**
 * The caller's inbox.
 *
 * @property requests The pending requests, newest first.
 */
@Serializable
data class ApprovalsResponse(val requests: List<ApprovalRequestResponse>)
