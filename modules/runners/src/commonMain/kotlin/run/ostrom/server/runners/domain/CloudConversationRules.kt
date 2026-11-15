// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/**
 * A refusal of the conversation routes, with the stable key TeamCode answers (`TeamFailure(code, status, message)`).
 *
 * @param kind The HTTP status it maps to.
 * @param type The `error.type` clients receive.
 * @param message What to fix.
 */
open class CloudConversationException(kind: ErrorKind, type: String, message: String) :
    OstromException(kind, message, type)

/** Bounds and checks of transcripts and inputs (TeamCode's `CloudConversationRules`, masking aside). */
@Suppress("TooManyFunctions") // TeamCode's one object of rules, kept whole.
object CloudConversationRules {
    /** The background task ids a stop takes (Claude Code's are short letters and digits). */
    val TASK_ID: Regex = Regex("[A-Za-z0-9_-]{1,64}")

    /** The model ids a session takes. */
    val MODEL: Regex = Regex("[a-zA-Z0-9._:/-]{1,120}")

    /** The effort values a session takes. */
    val EFFORT: Regex = Regex("[a-zA-Z0-9._:/-]{1,120}")

    /** The feeds a page is asked for: `main`, or the id of the `tool.call` that started a subagent. */
    val FEED: Regex = Regex("[A-Za-z0-9_.:-]{1,128}")

    /** The `feed` value of the main agent's own events. */
    const val MAIN_FEED: String = "main"

    /** Subagents one listing returns at most (the newest). */
    const val MAX_SUBAGENTS: Int = 200

    /** Longest message a member may send, in characters. */
    const val MAX_MESSAGE_CHARS: Int = 32_000
    const val MAX_PENDING_INPUTS: Int = 100
    const val MAX_PAGE: Int = 200

    /** The largest page of inputs (`limit` of `GET .../messages`). */
    const val MAX_INPUT_PAGE: Int = 100
    const val MAX_PERMISSION_ANSWERS: Int = 16

    /** Pending permission answers a session holds at most, outside [MAX_PENDING_INPUTS]. */
    const val MAX_PENDING_PERMISSION_ANSWERS: Int = 16
    const val MAX_PERMISSION_TEXT_CHARS: Int = 4_000

    /** Question and option indexes stay below this bound (Claude Code asks at most 4 questions of 4 options). */
    const val MAX_PERMISSION_INDEX: Int = 64

    /** Events a runner sends in one batch. */
    const val MAX_EVENTS_PER_BATCH: Int = 200

    /** The longest a runner's poll for inputs waits. */
    const val MAX_RUNNER_WAIT_MILLIS: Long = 20_000L

    /** A delivery not acknowledged within this time is offered again (the runner deduplicates by input id). */
    const val REDELIVERY_MILLIS: Long = 60_000L

    /** How many times an input is delivered before it is rejected. */
    const val MAX_ATTEMPTS: Int = 3

    /** The `turn` phases that end a turn, and with it every permission request still open in it. */
    val TURN_END_PHASES: Set<String> = setOf("completed", "interrupted", "failed")

    private val permissionRequestId = Regex("[\\x21-\\x7E]{1,128}")
    private val BARE_DENIAL = Regex("""\{"requestId":"(?:[^"\\]|\\["\\])*","behavior":"deny"\}""")

    /**
     * Whether [authorId]'s instructions are kept from the agent of [session] because it uses the owner's GitHub
     * access (audit M2): a non-owner, while the session's access is the owner's. Ostrom cannot tell here whether the
     * owner linked GitHub, so it refuses whatever the link (fail closed).
     *
     * @return True when only the owner instructs the agent.
     */
    fun ownerOnly(session: CloudSession, authorId: String): Boolean =
        authorId != session.ownerId && session.githubAccess == GitHubAccessMode.OWNER

    /**
     * The runner's text for a bare denial of [requestId]: the only shape of a permission input [onlyStops] lets
     * through.
     *
     * @return The encoded answer.
     */
    fun bareDenialText(requestId: String): String =
        "{\"requestId\":\"" + requestId.replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"behavior\":\"deny\"}"

    /**
     * The runner's text for the owner's standing approval of [requestId] (their bypass): an allow without a message.
     *
     * @return The encoded answer.
     */
    fun bypassAllowText(requestId: String): String =
        "{\"requestId\":\"" + requestId.replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"behavior\":\"allow\"}"

    /**
     * An input that only stops something (an interrupt, a task stop, a bare permission denial).
     *
     * @return True when it may reach an owner-only agent from anyone who may message it.
     */
    fun onlyStops(input: CloudInput): Boolean = input.kind == CloudInputKind.INTERRUPT ||
        (input.kind == CloudInputKind.STOP_TASK && input.text != null && TASK_ID.matches(input.text)) ||
        (input.kind == CloudInputKind.PERMISSION && input.text != null && BARE_DENIAL.matches(input.text))

    /** @throws CloudConversationException `invalid_task` for an id outside [TASK_ID]. */
    fun validateTaskId(taskId: String) {
        if (!TASK_ID.matches(taskId)) {
            throw invalid("invalid_task", "Send the id of a background task: 1 to 64 letters, digits, '-' or '_'.")
        }
    }

    /** @throws CloudConversationException `invalid_agent` for a model outside [MODEL]. */
    fun validateModel(model: String) {
        if (!MODEL.matches(model)) {
            throw invalid("invalid_agent", "Use a valid agent model: $OPTION_SHAPE")
        }
    }

    /** @throws CloudConversationException `invalid_agent` for an effort outside [EFFORT]. */
    fun validateEffort(effort: String) {
        if (!EFFORT.matches(effort)) {
            throw invalid("invalid_agent", "Use a valid agent effort: $OPTION_SHAPE")
        }
    }

    /** @throws CloudConversationException `invalid_message` for an empty, overlong or control-laden message. */
    fun validateMessage(text: String, files: Boolean = false) {
        // A message that carries files may have no text at all, never a blank one.
        if (!(files && text.isEmpty()) && !fits(text, MAX_MESSAGE_CHARS)) {
            throw invalid(
                "invalid_message",
                "Send a message of 1–$MAX_MESSAGE_CHARS characters without control characters (it may be empty " +
                    "when it carries files).",
            )
        }
    }

    /**
     * Request ids are the agent's own (Claude Code uses UUIDs); answers are short: a label index or a sentence each.
     *
     * @throws CloudConversationException `invalid_permission_answer` for a malformed answer.
     */
    @Suppress("ComplexCondition", "ThrowsCount") // TeamCode's checks, one refusal each.
    fun validatePermissionAnswer(answer: PermissionAnswer) {
        if (!isPermissionRequestId(answer.requestId)) {
            throw badAnswer("Send the requestId of a permission.request event.")
        }
        val texts = listOfNotNull(answer.message) + answer.answers.mapNotNull { it.text }
        val badText = texts.any { !fits(it, MAX_PERMISSION_TEXT_CHARS) }
        if (answer.answers.size > MAX_PERMISSION_ANSWERS || badText) {
            throw badAnswer(
                "Send at most $MAX_PERMISSION_ANSWERS answers and texts of 1–$MAX_PERMISSION_TEXT_CHARS characters.",
            )
        }
        if (answer.answers.isNotEmpty() && !answer.allow) throw badAnswer("Answers go with behavior allow.")
        val questions = answer.answers.map { it.question }
        if (questions.toSet().size != questions.size || questions.any { it !in 0 until MAX_PERMISSION_INDEX }) {
            throw badAnswer("Answer each question once, by its index (0 to ${MAX_PERMISSION_INDEX - 1}).")
        }
        val malformed = answer.answers.any {
            it.options.isEmpty() == (it.text == null) || it.options.toSet().size != it.options.size ||
                it.options.any { option -> option !in 0 until MAX_PERMISSION_INDEX }
        }
        if (malformed) {
            throw badAnswer(
                "Answer a question with distinct option indexes (0 to ${MAX_PERMISSION_INDEX - 1}) or with a text, " +
                    "not both.",
            )
        }
    }

    /**
     * Who may answer [request], and whether [answer] fits it. Approving or denying a tool use gated by an `ask` rule is
     * the owner's or an administrator's decision ([approver]); a question is answered by whoever may message.
     *
     * @throws CloudConversationException The refusal.
     */
    @Suppress("ThrowsCount", "CyclomaticComplexMethod") // TeamCode's checks, one refusal each.
    fun checkAnswer(
        request: PermissionRequestRecord,
        answer: PermissionAnswer,
        approver: Boolean,
        currentEpoch: Long = request.epoch,
        standing: Boolean = false,
    ) {
        // Who may decide comes first, so someone who may not learns nothing about the request's state.
        if (!request.question && !approver) {
            throw CloudConversationException(
                ErrorKind.FORBIDDEN,
                "permission_owner_only",
                "Only the session owner or an administrator may approve or deny this tool use.",
            )
        }
        // An execution that ended (a pause, a new pod) took its open requests with it.
        val ended = request.epoch != currentEpoch && request.state == PermissionRequestState.OPEN
        when (if (ended) PermissionRequestState.ENDED else request.state) {
            PermissionRequestState.OPEN -> Unit
            PermissionRequestState.EXPIRED -> throw conflict(
                "permission_request_expired",
                "This permission request expired without an answer and was denied.",
            )
            PermissionRequestState.ANSWERED ->
                throw conflict("permission_request_answered", "This permission request was already answered.")
            PermissionRequestState.ENDED ->
                throw conflict("permission_request_ended", "The turn of this permission request has ended.")
        }
        // Nobody may approve what nobody could read: an input too large for the transcript can only be denied.
        // A standing approval (the owner's bypass) was given in advance, seen or not.
        if (answer.allow && request.inputLeftOut && !standing) {
            throw invalid(
                "permission_input_unseen",
                "This request's input was too large to show in the transcript, so it can only be denied.",
            )
        }
        if (!request.question) {
            if (answer.answers.isNotEmpty()) {
                throw badAnswer("This request is a tool use, not a question: send no answers.")
            }
            return
        }
        if (answer.allow && answer.answers.isEmpty()) {
            throw badAnswer("Answer at least one question, or deny the request.")
        }
        checkQuestions(request.questions ?: return, answer)
    }

    @Suppress("ThrowsCount") // TeamCode's checks, one refusal each.
    private fun checkQuestions(questions: List<QuestionShape>, answer: PermissionAnswer) {
        val texts = answer.answers.mapNotNull { questions.getOrNull(it.question)?.text }
        if (texts.toSet().size != texts.size) {
            throw badAnswer("Two of these questions have the same text; answer only one of them.")
        }
        for (item in answer.answers) {
            val shape = questions.getOrNull(item.question)
                ?: throw badAnswer("The request has ${questions.size} questions, numbered from 0.")
            if (item.options.any { it >= shape.options } || (item.options.size > 1 && !shape.multiSelect)) {
                val takes = if (shape.multiSelect) "." else ", and takes one."
                throw badAnswer("Question ${item.question} has ${shape.options} options, numbered from 0$takes")
            }
        }
        // Claude Code reads one answer per question text: every one of them is answered (a repeated text once).
        val asked = questions.mapIndexed { index, shape -> shape.text ?: "#$index" }.toSet()
        val answered = answer.answers.map { questions[it.question].text ?: "#${it.question}" }.toSet()
        if (answer.allow && answered != asked) throw badAnswer("Answer each of the request's questions, or deny it.")
    }

    /** @return Whether [id] is a request id a member can answer with: printable ASCII, 1 to 128 characters. */
    fun isPermissionRequestId(id: String): Boolean = permissionRequestId.matches(id)

    /** @throws CloudConversationException `invalid_feed` for a feed outside [FEED]. */
    fun validateFeed(feed: String?) {
        if (feed != null && !FEED.matches(feed)) {
            throw invalid(
                "invalid_feed",
                "Use feed=main or the id of a subagent's tool call (at most 128 letters, digits and _ . : -).",
            )
        }
    }

    /** @throws CloudConversationException `invalid_limit` or `invalid_cursor` for a malformed page. */
    fun validatePage(limit: Int, after: Long?, before: Long?) {
        if (limit !in 1..MAX_PAGE) throw invalid("invalid_limit", "Use a page size between 1 and $MAX_PAGE.")
        val cursor = when {
            after != null && before != null -> "Send after or before, not both."
            (after ?: 0) < 0 || (before ?: 1) < 1 -> "Use a non-negative event sequence."
            else -> return
        }
        throw invalid("invalid_cursor", cursor)
    }

    /** @return A 400 with [type]. */
    fun invalid(type: String, message: String): CloudConversationException =
        CloudConversationException(ErrorKind.INVALID_REQUEST, type, message)

    /** @return A 409 with [type]. */
    fun conflict(type: String, message: String): CloudConversationException =
        CloudConversationException(ErrorKind.CONFLICT, type, message)

    private fun badAnswer(message: String) = invalid("invalid_permission_answer", message)

    private val ALLOWED = setOf('\n', '\t', '\r')

    private const val OPTION_SHAPE = "1 to 120 letters, digits, '.', '_', ':', '/' or '-'."

    /** A text that is not blank, at most [max] characters, without control characters but line breaks and tabs. */
    private fun fits(text: String, max: Int): Boolean =
        text.isNotBlank() && text.length <= max && text.none { it.isISOControl() && it !in ALLOWED }
}
