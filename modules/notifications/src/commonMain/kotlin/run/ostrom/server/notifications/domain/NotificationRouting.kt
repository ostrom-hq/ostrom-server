// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0),
// domain/src/main/kotlin/me/nathanfallet/teamcode/domain/notifications/Notifications.kt; see NOTICE.
package run.ostrom.server.notifications.domain

import kotlinx.serialization.json.JsonPrimitive
import run.ostrom.core.api.models.NotificationKinds
import run.ostrom.server.kernel.events.JournalEntry
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.kernel.events.WaitSummary
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What a member is told about (docs/notifications.md). A kind is one switch per channel in the member's settings, on
 * until they turn it off. The [wire] name is stored, so it never changes once released.
 *
 * @property wire The kind's name on the wire and in the database.
 */
enum class NotificationKind(val wire: String) {
    /** A person is asked something: a wait assigned to them, or a permission request or a question of their agent. */
    WAITING(NotificationKinds.WAITING),

    /** The agent of their session finished its turn. */
    TURN_COMPLETED(NotificationKinds.TURN_COMPLETED),

    /** The agent's turn failed; a `turn_failed` wait offers to retry it. */
    TURN_FAILED(NotificationKinds.TURN_FAILED),

    /** Their session ended in `failed`. */
    SESSION_FAILED(NotificationKinds.SESSION_FAILED),
    ;

    /** Reads a wire name. */
    companion object {
        /**
         * @param value A wire name.
         *
         * @return Its kind, or null for one this server does not know.
         */
        fun parse(value: String): NotificationKind? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * One notification for one member, whatever the channel: what happened, said neutrally. A channel renders it its own
 * way (a push, later a Slack or Discord message) and keeps its own destinations. [title] and [body] are short English
 * text; a client may render [kind] with [sessionId] or [waitId] in its own language instead.
 *
 * @property id The notification's id, the delivery's.
 * @property orgId The organization.
 * @property memberId The member told.
 * @property kind What it is about.
 * @property title Its title.
 * @property body Its text.
 * @property sessionId The session a tap opens.
 * @property waitId The wait a tap opens.
 * @property collapseKey What groups the notifications that replace one another (a newer one about the same wait).
 * @property createdAt When it was made.
 * @property eventSeq The journal event it comes from.
 */
data class Notification(
    val id: Uuid,
    val orgId: Uuid,
    val memberId: Uuid,
    val kind: NotificationKind,
    val title: String,
    val body: String,
    val sessionId: String?,
    val waitId: String?,
    val collapseKey: String,
    val createdAt: Instant,
    val eventSeq: Long,
)

/**
 * What one attempt on a channel produced.
 *
 * @property outcome The outcome.
 * @property error A short code of ours, never a provider's answer.
 */
data class ChannelResult(val outcome: Outcome, val error: String? = null) {
    /** The outcomes. */
    enum class Outcome {
        /** Delivered. */
        DELIVERED,

        /** Worth another attempt later: the provider was unreachable, failing or asking to slow down. */
        RETRY,

        /** Refused for good, or nothing left to deliver to. */
        FAILED,
    }

    /** The results. */
    companion object {
        /** Delivered. */
        val DELIVERED: ChannelResult = ChannelResult(Outcome.DELIVERED)

        /**
         * @param error What failed.
         *
         * @return A retryable failure.
         */
        fun retry(error: String): ChannelResult = ChannelResult(Outcome.RETRY, error)

        /**
         * @param error What was refused.
         *
         * @return A failure for good.
         */
        fun failed(error: String): ChannelResult = ChannelResult(Outcome.FAILED, error)
    }
}

/**
 * A way to reach a member: push to their phones today, a Slack or Discord message later. A channel owns its
 * destinations and how a notification looks there; the router decides what is sent and to whom, the same for every
 * channel. The module that offers a channel binds it in Koin; the server offers the channels bound.
 */
interface NotificationChannel {
    /** Stable and stored with settings and deliveries: `push`, later `slack`, `discord`. At most 16 characters. */
    val id: String

    /**
     * @param orgId The organization.
     * @param memberIds The members to tell.
     *
     * @return Those of [memberIds] this channel can reach now, in one read whatever their number.
     */
    suspend fun reachable(orgId: Uuid, memberIds: Set<Uuid>): Set<Uuid>

    /**
     * Delivers each notification to every destination of its member on this channel, the batch together so its
     * destinations are read once. Bounded by the caller's deadline.
     *
     * @param notifications The batch.
     *
     * @return The result of each notification, by id; a missing one is tried again.
     */
    suspend fun deliver(notifications: List<Notification>): Map<Uuid, ChannelResult>
}

/**
 * A member's switch for one kind on one channel. Without a stored switch, the kind is on.
 *
 * @property channel The channel's id.
 * @property kind The kind.
 * @property enabled Whether the member is told.
 */
data class NotificationSetting(val channel: String, val kind: NotificationKind, val enabled: Boolean)

/**
 * A switch a member turned off, as the router skips it.
 *
 * @property memberId The member.
 * @property channel The channel's id.
 * @property kind The kind.
 */
data class SwitchedOff(val memberId: Uuid, val channel: String, val kind: NotificationKind)

/**
 * A journal event with what it refers to, read in the same pass so the rules need no other read.
 *
 * @property entry The event.
 * @property wait The wait it names, null when it names none or the wait is gone.
 * @property sessionName The name of the session it is about, if it has one.
 */
data class NotificationSource(val entry: JournalEntry, val wait: WaitSummary?, val sessionName: String? = null)

/**
 * One notification to queue: who, about what, without the channel (the router multiplies it by channels).
 *
 * @property eventSeq The journal event it comes from.
 * @property memberId The member told.
 * @property kind What it is about.
 * @property title Its title.
 * @property body Its text.
 * @property sessionId The session a tap opens.
 * @property waitId The wait a tap opens.
 * @property collapseKey What groups the notifications that replace one another.
 */
data class NotificationDraft(
    val eventSeq: Long,
    val memberId: Uuid,
    val kind: NotificationKind,
    val title: String,
    val body: String,
    val sessionId: String?,
    val waitId: String?,
    val collapseKey: String,
)

/**
 * Turns journal events into notifications (TeamCode's `NotificationRules`). Pure: every read happened before. A wait
 * a timer or an event resolves asks nobody, and one answered or withdrawn before the pass is old news. The question's
 * fields, an answer or a tool's input are never put in a notification: only the wait's title.
 */
object NotificationRules {
    /**
     * The journal types that can notify; the router reads only these. TeamCode's `cloud.session.waiting`,
     * `cloud.turn` and `cloud.session.ended` join them when cloud sessions publish them.
     */
    val SOURCE_TYPES: Set<OrgEventType> = setOf(OrgEventType.WAIT_CREATED)

    /** The longest title. */
    const val MAX_TITLE: Int = 120

    /** The longest text. */
    const val MAX_BODY: Int = 200

    /** The title of a notification about a session without a name, or about no session. */
    const val DEFAULT_TITLE: String = "Ostrom"

    /** The text of a failed turn's notification. */
    const val TURN_FAILED_BODY: String = "The agent's turn failed."

    private const val HUMAN = "human"
    private const val TURN_FAILED_WAIT = "turn_failed"

    /**
     * @param source An event and what it names.
     *
     * @return The notification it makes, or null when it makes none.
     */
    fun draft(source: NotificationSource): NotificationDraft? {
        val entry = source.entry
        if (entry.type != OrgEventType.WAIT_CREATED) return null
        val wait = source.wait?.takeIf { it.open && it.resolver == HUMAN }
        // A wait asked of something else than a member (an agent's name) tells no person.
        val member = wait?.let { Uuid.parseOrNull(it.assigneeId) } ?: return null
        val title = source.sessionName?.takeIf { it.isNotBlank() }?.take(MAX_TITLE) ?: DEFAULT_TITLE
        val failed = wait.kind == TURN_FAILED_WAIT
        return NotificationDraft(
            eventSeq = entry.seq,
            memberId = member,
            kind = if (failed) NotificationKind.TURN_FAILED else NotificationKind.WAITING,
            title = title,
            body = if (failed) TURN_FAILED_BODY else wait.title.take(MAX_BODY),
            sessionId = wait.sessionId,
            waitId = wait.id,
            collapseKey = "wait:${wait.id}",
        )
    }

    /**
     * @param entry A `cloud.wait.created` event.
     *
     * @return The wait it names: its `waitId`, else its subject.
     */
    fun waitIdOf(entry: JournalEntry): String = (entry.data["waitId"] as? JsonPrimitive)?.content ?: entry.subjectId
}
