// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.automations.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Where a wait is in its life; final states never change.
 *
 * @property wire The contract's value.
 */
enum class WaitStatus(val wire: String) {
    /** Waiting for its answer. */
    OPEN("open"),

    /** Answered: the first answer won. */
    RESOLVED("resolved"),

    /** Withdrawn by its asker, its assignee or an administrator. */
    CANCELLED("cancelled"),
}

/**
 * The kinds of field a wait asks for.
 *
 * @property wire The contract's value.
 */
@Serializable
enum class WaitFieldType(val wire: String) {
    /** A string. */
    @SerialName("text")
    TEXT("text"),

    /** One of the options, or a list of them when the field is multiple. */
    @SerialName("choice")
    CHOICE("choice"),

    /** `true` or `false`. */
    @SerialName("confirm")
    CONFIRM("confirm"),

    /** A finite number. */
    @SerialName("number")
    NUMBER("number"),

    /** A calendar date, `YYYY-MM-DD`. */
    @SerialName("date")
    DATE("date"),

    /** No value: it shows its help. */
    @SerialName("info")
    INFO("info"),
}

/**
 * A field of a wait.
 *
 * @property id Its id, unique in the wait.
 * @property type Its type.
 * @property label What it asks.
 * @property help More detail.
 * @property required Whether every answer fills it.
 * @property default The value proposed.
 * @property options The choices of a `choice` field.
 * @property multiple Whether a `choice` field takes several options.
 * @property allowOther Whether a `choice` field accepts a free answer.
 * @property maxLength The longest `text` answer.
 */
@Serializable
data class WaitField(
    val id: String,
    val type: WaitFieldType,
    val label: String,
    val help: String? = null,
    val required: Boolean = false,
    val default: JsonElement? = null,
    val options: List<String> = emptyList(),
    val multiple: Boolean = false,
    val allowOther: Boolean = false,
    val maxLength: Int? = null,
)

/**
 * A button that ends a wait.
 *
 * @property id Its id, unique in the wait.
 * @property label Its text.
 * @property requires The fields this action needs filled.
 * @property style `primary`, `secondary` or `danger`.
 */
@Serializable
data class WaitAction(
    val id: String,
    val label: String,
    val requires: List<String> = emptyList(),
    val style: String? = null,
)

/**
 * Something to look at with a wait.
 *
 * @property kind `artifact`, `session` (by [id]) or `url`.
 * @property id The artifact's or session's id.
 * @property url An `https` address.
 * @property title Its label.
 */
@Serializable
data class WaitRef(
    val kind: String,
    val id: String? = null,
    val url: String? = null,
    val title: String? = null,
)

/**
 * The question of a wait.
 *
 * @property title Its title.
 * @property body Markdown.
 * @property fields What to fill.
 * @property actions The buttons that end it; one `done` when none is given.
 * @property refs What to look at.
 */
@Serializable
data class WaitSpec(
    val title: String,
    val body: String? = null,
    val fields: List<WaitField> = emptyList(),
    val actions: List<WaitAction> = emptyList(),
    val refs: List<WaitRef> = emptyList(),
)

/**
 * The answer of a wait.
 *
 * @property action The action chosen.
 * @property values The values by field id.
 * @property author The member who answered.
 * @property answeredAt When.
 */
data class WaitAnswer(
    val action: String,
    val values: Map<String, JsonElement>,
    val author: String,
    val answeredAt: Instant,
)

/**
 * A question to a person, asked by an agent (`ask_human`) or a member, that the session waits for. The first answer
 * wins.
 *
 * @property id Its id.
 * @property orgId The organization.
 * @property kind `human_request`.
 * @property status Where it is.
 * @property sessionId The session that waits, if any.
 * @property runId The workflow run that waits, if any.
 * @property assignee The member asked.
 * @property createdBy The member whose credential asked.
 * @property question The question.
 * @property answer The answer, once resolved.
 * @property createdAt When it was asked.
 * @property resolvedAt When it was answered or cancelled.
 * @property idempotencyKey The asker's key: asking again with it returns this wait.
 */
data class Wait(
    val id: Uuid,
    val orgId: Uuid,
    val kind: String,
    val status: WaitStatus,
    val sessionId: String?,
    val runId: String?,
    val assignee: String,
    val createdBy: String,
    val question: WaitSpec,
    val answer: WaitAnswer?,
    val createdAt: Instant,
    val resolvedAt: Instant?,
    val idempotencyKey: String?,
)

/**
 * The member a request acts for.
 *
 * @property orgId Their organization.
 * @property memberId Their id.
 * @property admin Whether they administer the organization.
 * @property interactive Whether they signed in themselves (not a key nor an agent's credential): only then does an
 *   administrator answer or cancel a wait asked of someone else.
 * @property agent Whether an agent token acts for its person, on the waits MCP server: it asks any member (its person
 *   by default), and reads and cancels only the waits its person asked, never one someone else asked of its person.
 * @property session For an agent token, the segment of its session open now, which the server found: it asks there.
 * @property segments For an agent token, the segments of its session its launch opened, the open one included: the
 *   waits asked there are its own, also after the launcher reopened the session.
 */
data class WaitCaller(
    val orgId: Uuid,
    val memberId: String,
    val admin: Boolean,
    val interactive: Boolean,
    val agent: Boolean = false,
    val session: String? = null,
    val segments: List<String>? = null,
) {
    /** Whether this caller acts as an administrator here. */
    val actsAsAdmin: Boolean get() = admin && interactive
}

/** How storing a new wait went. */
enum class CreateOutcome {
    /** Stored. */
    CREATED,

    /** Too many waits of the same scope are open: nothing stored. */
    AT_LIMIT,

    /** The asker already used this idempotency key: nothing stored. */
    DUPLICATE,
}
