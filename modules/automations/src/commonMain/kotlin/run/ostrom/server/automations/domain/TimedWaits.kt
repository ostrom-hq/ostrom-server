// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.domain

import run.ostrom.server.kernel.http.OstromJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * How a wait for a time or an event is asked (`createWait`'s `at`, `after`, `source`, `timeoutSeconds` and
 * `resumeMessage`).
 *
 * @property at For a time, the instant it resolves, ISO-8601 in UTC.
 * @property after For a time, seconds from now.
 * @property source For an event, `webhook` (the only one, by default).
 * @property timeoutSeconds For an event, how long it waits before it times out.
 * @property resumeMessage A line added to the message that wakes the session.
 */
data class WaitSchedule(
    val at: String? = null,
    val after: Long? = null,
    val source: String? = null,
    val timeoutSeconds: Long? = null,
    val resumeMessage: String? = null,
) {
    /** Whether it says nothing. */
    val empty: Boolean get() = this == WaitSchedule()
}

/**
 * What an outside caller posted to an event address, checked.
 *
 * @property result `success` or `failure` by convention.
 * @property url An https link.
 * @property note Free text.
 */
data class WaitEvent(val result: String, val url: String?, val note: String?) {
    /** The values of the answer it becomes. */
    val values: Map<String, JsonElement>
        get() = listOfNotNull("result" to result, url?.let { "url" to it }, note?.let { "note" to it })
            .associate { (key, value) -> key to JsonPrimitive(value) }
}

/** The checks of the waits that resolve by themselves (TeamCode's `WaitRules`, ported). */
internal object TimedWaitRules {
    const val TIMED = "timed"
    const val WEBHOOK = "webhook"
    const val MIN_SECONDS = 30L
    const val MAX_AFTER_SECONDS = 30L * 24 * 3600
    const val DEFAULT_TIMEOUT_SECONDS = 24L * 3600
    const val MAX_TIMEOUT_SECONDS = 7L * 24 * 3600
    const val MAX_RESUME_MESSAGE = 2_000
    const val MAX_EVENT_BYTES = 16 * 1024
    const val MAX_EVENT_RESULT = 64
    const val MAX_EVENT_NOTE = 2_000
    const val MAX_EVENT_URL = 2_000

    /** The answer's action of a time that came. */
    const val TIME_REACHED = "time_reached"

    /** The answer's action of an event that arrived. */
    const val EVENT_RECEIVED = "event_received"

    /** The answer's action of an event wait that timed out. */
    const val TIMED_OUT = "timed_out"

    /** Who answered a wait that resolved by itself. */
    const val SYSTEM = "ostrom"

    /**
     * The resolver a request names: `human` when it names none.
     *
     * @throws InvalidWaitException For any other value.
     */
    fun resolver(wire: String?): WaitResolver = if (wire == null) {
        WaitResolver.HUMAN
    } else {
        WaitResolver.entries.firstOrNull { it.wire == wire }
            ?: throw InvalidWaitException("resolver is human, time or event.")
    }

    /**
     * Checks a wait for a time or an event and returns when it resolves.
     *
     * @param resolver [WaitResolver.TIME] or [WaitResolver.EVENT].
     * @param spec Its question: a title, maybe a body and refs, never fields nor actions.
     * @param schedule What the request says of it.
     * @param now The time.
     * @param secretHash For an event, the hash of its new secret.
     *
     * @return Its timer.
     *
     * @throws InvalidWaitException With what to fix.
     */
    fun timer(
        resolver: WaitResolver,
        spec: WaitSpec,
        schedule: WaitSchedule,
        now: Instant,
        secretHash: String?,
    ): WaitTimer {
        requireWait(spec.fields.isEmpty() && spec.actions.isEmpty()) {
            "A wait for a time or an event asks nobody: it has no fields and no actions."
        }
        schedule.resumeMessage?.let { message ->
            requireWait(message.isNotBlank() && message.length <= MAX_RESUME_MESSAGE && !message.hasControls()) {
                "resumeMessage is 1 to $MAX_RESUME_MESSAGE characters."
            }
        }
        return if (resolver == WaitResolver.TIME) {
            WaitTimer(resolver, dueAt(schedule, now), resumeMessage = schedule.resumeMessage)
        } else {
            requireWait(schedule.at == null && schedule.after == null) { "at and after belong to waits for a time." }
            requireWait(schedule.source == null || schedule.source == WEBHOOK) { "source is $WEBHOOK." }
            val timeout = schedule.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS
            requireWait(timeout in MIN_SECONDS..MAX_TIMEOUT_SECONDS) {
                "timeoutSeconds is $MIN_SECONDS seconds to 7 days ($MAX_TIMEOUT_SECONDS seconds)."
            }
            WaitTimer(resolver, now + timeout.seconds, WEBHOOK, schedule.resumeMessage, secretHash)
        }
    }

    private fun dueAt(schedule: WaitSchedule, now: Instant): Instant {
        requireWait(schedule.source == null && schedule.timeoutSeconds == null) {
            "source and timeoutSeconds belong to waits for an event."
        }
        val after = schedule.after
        val at = schedule.at
        return when {
            after != null -> {
                requireWait(at == null) { EITHER }
                requireWait(after in MIN_SECONDS..MAX_AFTER_SECONDS) {
                    "after is $MIN_SECONDS seconds to 30 days ($MAX_AFTER_SECONDS seconds)."
                }
                now + after.seconds
            }
            at != null -> dueAt(at, now)
            else -> throw InvalidWaitException(EITHER)
        }
    }

    private fun dueAt(text: String, now: Instant): Instant {
        val at = instant(text)
            ?: throw InvalidWaitException("at is an ISO-8601 instant in UTC, such as 2026-11-02T09:00:00Z.")
        requireWait(at > now) { "at must be in the future." }
        requireWait(at >= now + MIN_SECONDS.seconds) { "at is at least $MIN_SECONDS seconds from now." }
        requireWait(at <= now + MAX_AFTER_SECONDS.seconds) { "at is at most 30 days from now." }
        // To the millisecond, as stored.
        return Instant.fromEpochMilliseconds(at.toEpochMilliseconds())
    }

    private const val EITHER = "Give either at or after, not both nor none."

    private fun instant(text: String): Instant? = try {
        Instant.parse(text)
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * What an outside caller posted: an empty body is a success; otherwise a JSON object of `result` (1 to 64
     * characters, `success` by default), an https `url` and a `note`.
     *
     * @throws InvalidWaitException With what to fix.
     */
    fun event(body: ByteArray): WaitEvent {
        val text = body.decodeToString()
        if (text.isBlank()) return WaitEvent(SUCCESS, null, null)
        val json = try {
            OstromJson.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: throw InvalidWaitException("Post a JSON object such as {\"result\": \"success\"}.")
        val result = json.text("result") ?: SUCCESS
        requireWait(result.isNotBlank() && result.length <= MAX_EVENT_RESULT && result.none { it.isISOControl() }) {
            "result is 1 to $MAX_EVENT_RESULT characters, such as success or failure."
        }
        val url = json.text("url")
        requireWait(url == null || (url.startsWith("https://") && url.length <= MAX_EVENT_URL && url.none(::unsafe))) {
            "url is an https address without spaces."
        }
        val note = json.text("note")
        requireWait(note == null || (note.length <= MAX_EVENT_NOTE && !note.hasControls())) {
            "note is at most $MAX_EVENT_NOTE characters."
        }
        return WaitEvent(result, url, note)
    }

    private fun JsonObject.text(key: String): String? {
        val value = this[key]
        if (value == null || value == JsonNull) return null
        val primitive = value as? JsonPrimitive
        if (primitive == null || !primitive.isString) throw InvalidWaitException("$key is a text.")
        return primitive.content
    }

    private fun unsafe(char: Char): Boolean = char.isWhitespace() || char.isISOControl()

    /** Control characters other than a newline or a tab. */
    private fun String.hasControls(): Boolean = any { it.isISOControl() && it != '\n' && it != '\t' }

    private const val SUCCESS = "success"
}
