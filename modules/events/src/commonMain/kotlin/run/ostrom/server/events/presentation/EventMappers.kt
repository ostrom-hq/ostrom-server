// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.events.presentation

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import run.ostrom.core.api.OstromJson
import run.ostrom.core.api.models.EventGap
import run.ostrom.core.api.models.OrgEvent
import run.ostrom.server.events.domain.JournalEvent

/**
 * The contract's view of a journal event (`OrgEvent`). Data that is not a JSON object (never written so, but the
 * journal is read across versions) is sent as an empty object rather than failing the stream.
 *
 * @return The event.
 */
fun JournalEvent.toOrgEvent(): OrgEvent = OrgEvent(
    seq = seq,
    type = type,
    subject = subject,
    subjectId = subjectId,
    memberId = memberId?.toString(),
    at = at.toString(),
    data = dataObject(data),
)

private fun dataObject(text: String): Map<String, JsonElement> = try {
    OstromJson.parseToJsonElement(text) as? JsonObject ?: emptyMap()
} catch (_: SerializationException) {
    emptyMap()
}

/** One SSE frame of an event: its sequence as the id an `EventSource` resumes from, its type as the event name. */
internal fun eventFrame(event: JournalEvent): String {
    val data = OstromJson.encodeToString(OrgEvent.serializer(), event.toOrgEvent())
    return "id: ${event.seq}\nevent: ${event.type}\ndata: $data\n\n"
}

/** The frame that says events after the client's cursor were purged, and where the stream goes on. */
internal fun gapFrame(oldestSeq: Long): String =
    "event: gap\ndata: ${OstromJson.encodeToString(EventGap.serializer(), EventGap(oldestSeq))}\n\n"

/** The last frame of a stream whose credential stopped being valid. */
internal const val DENIED_FRAME: String = "event: denied\ndata: {}\n\n"
