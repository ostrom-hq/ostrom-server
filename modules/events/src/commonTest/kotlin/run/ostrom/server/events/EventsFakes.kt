// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.events

import run.ostrom.server.events.domain.EventAudience
import run.ostrom.server.events.domain.EventPage
import run.ostrom.server.events.domain.JournalEvent
import run.ostrom.server.events.domain.OrgEventsRepository
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** An organization's journal in memory, read as the PostgreSQL repository reads it. */
class InMemoryEvents(var head: Long = 0, var oldest: Long? = null) : OrgEventsRepository {
    val events = mutableListOf<JournalEvent>()
    val reads = mutableListOf<Long>()
    val purges = mutableListOf<Pair<Instant, Int>>()
    val erased = mutableListOf<Uuid>()
    var failure: Throwable? = null

    /** Appends an event of [member] (null: the whole organization's), at the next sequence. */
    fun add(member: Uuid?, type: String = "cloud.todo"): JournalEvent {
        head += 1
        val event = JournalEvent(head, type, "session", "s-$head", member, Instant.parse("2026-11-13T10:00:00Z"), "{}")
        events += event
        return event
    }

    override suspend fun head(orgId: Uuid): Long = head

    override suspend fun oldest(orgId: Uuid): Long? = oldest

    override suspend fun read(
        orgId: Uuid,
        audience: EventAudience,
        after: Long,
        types: Set<String>?,
        limit: Int,
    ): EventPage {
        reads += after
        if (head <= after) return EventPage(emptyList(), after, false)
        val rows = events.filter { it.seq > after && (types == null || it.type in types) && audience.sees(it.memberId) }
            .take(limit + 1)
        val page = rows.take(limit)
        return if (rows.size > limit) EventPage(page, page.last().seq, true) else EventPage(page, head, false)
    }

    override suspend fun purge(before: Instant, limit: Int): Int {
        failure?.let { throw it }
        purges += before to limit
        return 0
    }

    override suspend fun erase(orgId: Uuid) {
        erased += orgId
    }
}

/** Keeps what is logged. */
class RecordingLogger : Logger {
    val lines = mutableListOf<Pair<String, Map<String, Any?>>>()

    override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
        lines += message to fields
    }

    override fun with(fields: Map<String, Any?>): Logger = this
}
