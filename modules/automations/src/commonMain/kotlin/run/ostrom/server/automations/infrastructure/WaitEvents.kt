// SPDX-License-Identifier: Apache-2.0
// The wait events of TeamCode's team feed (https://github.com/nathanfallet/teamcode, Apache-2.0,
// domain/src/main/kotlin/me/nathanfallet/teamcode/domain/team/TeamEvents.kt): `cloud.wait.created`, `.resolved`,
// `.cancelled`.
package run.ostrom.server.automations.infrastructure

import run.ostrom.server.automations.domain.Wait
import run.ostrom.server.kernel.events.OrgEventDraft
import run.ostrom.server.kernel.events.OrgEventPublisher
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.kernel.database.JdbcDatabase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import kotlin.uuid.Uuid

/**
 * The event of [type] about this wait, as [OrgEventType] documents it: `{waitId, sessionId?, waitKind, assigneeId}`,
 * and the [action] of an answer. It belongs to the assignee (who sees it besides the owners and the admins); an
 * assignee that is not a member id (an agent) leaves it to the owners and the admins. Never the question, the
 * answer's values or a secret.
 */
internal fun Wait.event(type: OrgEventType, action: String? = null): OrgEventDraft {
    val data = buildMap {
        put("waitId", JsonPrimitive(id.toString()))
        sessionId?.let { put("sessionId", JsonPrimitive(it)) }
        put("waitKind", JsonPrimitive(kind))
        put("assigneeId", JsonPrimitive(assignee))
        action?.let { put("action", JsonPrimitive(it)) }
    }
    return OrgEventDraft(type, id.toString(), Uuid.parseOrNull(assignee), JsonObject(data))
}

/**
 * Runs the update of [change], which returns the waits it changed, confined to [orgId] (`inOrg`), and publishes the
 * [event] of each in the same transaction: the events commit, or roll back, with the change.
 */
internal suspend fun JdbcDatabase.changeInOrg(
    orgId: Uuid,
    events: OrgEventPublisher,
    event: (Wait) -> OrgEventDraft,
    change: JdbcTransaction.() -> List<Wait>,
): List<Wait> = inOrg(orgId) {
    val changed = change()
    events.publishIn(this, orgId, changed.map(event))
    changed
}
