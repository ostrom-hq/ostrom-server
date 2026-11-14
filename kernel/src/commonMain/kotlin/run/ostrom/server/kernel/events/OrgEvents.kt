// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.events

import io.github.smyrgeorge.sqlx4k.Transaction
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/**
 * The types of the organization's event feed (`GET /api/v1/events`, `events.v1`) and of its outgoing webhooks. A new
 * kind of event is one entry here and one [OrgEventPublisher] call where its change is written: the journal, the
 * stream, the audience rules and the webhooks need nothing else (TeamCode's `TeamEventType`). The [wire] name is the
 * SSE `event:` field and is stored, so it never changes once released.
 *
 * @property wire The type's name on the wire.
 * @property subject What its events are about (`wait`, `workflow_run`, `session`), the `subject` of each event.
 */
enum class OrgEventType(val wire: String, val subject: String) {
    /**
     * A wait was asked of a person: `{waitId, sessionId?, waitKind, assigneeId}`. It belongs to the assignee (who is
     * not always the session's owner); the subject is the wait.
     */
    WAIT_CREATED("cloud.wait.created", "wait"),

    /** A wait was answered: `{waitId, sessionId?, waitKind, assigneeId, action}`. */
    WAIT_RESOLVED("cloud.wait.resolved", "wait"),

    /** A wait was withdrawn: `{waitId, sessionId?, waitKind, assigneeId}`. */
    WAIT_CANCELLED("cloud.wait.cancelled", "wait"),

    /**
     * A workflow run changed in a way a screen shows (started, a step bound or done, ended):
     * `{runId, workflowId, status, ownerId, parentRunId?}`, never its inputs, outputs or failure. It belongs to the
     * run's starter; the subject is the run.
     */
    WORKFLOW_RUN_UPDATED("cloud.workflow.run.updated", "workflow_run"),

    /**
     * A session's todo changed in a way a screen shows: `{sessionId, status, done, total}`. It belongs to the
     * session's owner; the subject is the session.
     */
    TODO_UPDATED("cloud.todo", "session"),
    ;

    /** Reads a wire name. */
    companion object {
        /**
         * The type of a wire name.
         *
         * @param wire The name, as a client or the journal gives it.
         *
         * @return The type, or null for a name this server does not know.
         */
        fun parse(wire: String): OrgEventType? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * One event a module publishes, about a change it writes.
 *
 * @property type The event's type.
 * @property subjectId Which subject it is about (a wait's id, a run's id).
 * @property memberId The member the subject belongs to, who sees the event besides the owners and the admins; null
 *   for an event of the whole organization, which only they see.
 * @property data The type's facts: identifiers and short labels, at most [MAX_EVENT_DATA] characters as JSON, never a
 *   transcript, a prompt, a secret or a value a person typed.
 */
data class OrgEventDraft(
    val type: OrgEventType,
    val subjectId: String,
    val memberId: Uuid?,
    val data: JsonObject = JsonObject(emptyMap()),
)

/** The longest [OrgEventDraft.data] the journal takes, as JSON text: an event names things, it never carries them. */
const val MAX_EVENT_DATA: Int = 4096

/**
 * Appends events to an organization's feed, in one gap-free sequence per organization that every replica reads
 * (PostgreSQL, the events module). The events module binds it; until it does,
 * [serverKoin][run.ostrom.server.kernel.module.serverKoin] binds [DiscardingOrgEventPublisher], so a module tested
 * alone starts and publishes nothing.
 *
 * An event should exist exactly when its change committed: a repository that writes the change publishes with
 * [publishIn], in the same transaction and after its other writes (the organization's cursor row is the one lock every
 * publisher of an organization shares; taking it last keeps the lock order the same everywhere). [publish] opens its
 * own transaction, for a use case that has none: call it after the change committed, accepting that a crash in
 * between loses the event.
 */
interface OrgEventPublisher {
    /**
     * Appends [events] in order, in their own transaction.
     *
     * @param orgId The organization.
     * @param events The events; none is a no-op.
     *
     * @throws IllegalArgumentException When an event's data is longer than [MAX_EVENT_DATA].
     */
    suspend fun publish(orgId: Uuid, events: List<OrgEventDraft>)

    /**
     * Appends [events] in order inside [transaction], an organization's transaction (`withOrganization` or `inOrg`):
     * they commit, or roll back, with the change they report.
     *
     * @param transaction The caller's transaction, confined to [orgId].
     * @param orgId The organization.
     * @param events The events; none is a no-op.
     *
     * @throws IllegalArgumentException When an event's data is longer than [MAX_EVENT_DATA].
     */
    suspend fun publishIn(transaction: Transaction, orgId: Uuid, events: List<OrgEventDraft>)
}

/** Publishes nothing: the default for a server built without the events module (a module's own tests). */
class DiscardingOrgEventPublisher : OrgEventPublisher {
    override suspend fun publish(orgId: Uuid, events: List<OrgEventDraft>) = Unit

    override suspend fun publishIn(transaction: Transaction, orgId: Uuid, events: List<OrgEventDraft>) = Unit
}
