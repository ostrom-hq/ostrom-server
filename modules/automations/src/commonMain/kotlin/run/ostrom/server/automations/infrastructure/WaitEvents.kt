// SPDX-License-Identifier: Apache-2.0
// The wait events of TeamCode's team feed (https://github.com/nathanfallet/teamcode, Apache-2.0,
// domain/src/main/kotlin/me/nathanfallet/teamcode/domain/team/TeamEvents.kt): `cloud.wait.created`, `.resolved`, `.cancelled`.
package run.ostrom.server.automations.infrastructure

import run.ostrom.server.automations.domain.Wait
import run.ostrom.server.kernel.events.OrgEventDraft
import run.ostrom.server.kernel.events.OrgEventPublisher
import run.ostrom.server.kernel.events.OrgEventType
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid

/**
 * The event of [type] about this wait, as [OrgEventType] documents it: `{waitId, sessionId?, waitKind, assigneeId}`,
 * and the [action] of an answer. It belongs to the assignee (who sees it besides the owners and the admins); an
 * assignee that is not a member id leaves it to them. Never the question, the answer's values or a secret.
 */
internal fun Wait.event(type: OrgEventType, action: String? = null): OrgEventDraft {
    val data = buildMap {
        put("waitId", JsonPrimitive(id.toString()))
        sessionId?.let { put("sessionId", JsonPrimitive(it)) }
        put("waitKind", JsonPrimitive(kind))
        put("assigneeId", JsonPrimitive(assignee))
        action?.let { put("action", JsonPrimitive(it)) }
    }
    return OrgEventDraft(type, id.toString(), memberIdOf(assignee), JsonObject(data))
}

/** A member's id, or null for an assignee written otherwise. */
private fun memberIdOf(assignee: String): Uuid? = try {
    Uuid.parse(assignee)
} catch (_: IllegalArgumentException) {
    null
}

/**
 * Runs the update of [statement], which returns the waits it changed, confined to [orgId] ([inTenant]), and publishes
 * the [event] of each in the same transaction: the events commit, or roll back, with the change.
 */
internal suspend fun Driver.changeInOrg(
    orgId: Uuid,
    events: OrgEventPublisher,
    event: (Wait) -> OrgEventDraft,
    statement: () -> Statement,
): List<Wait> = inTenant(orgId) {
    val changed = fetchAll(statement()).getOrThrow().map { it.toWait() }
    events.publishIn(this, orgId, changed.map(event))
    changed
}
