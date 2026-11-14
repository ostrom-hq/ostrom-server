// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.infrastructure

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import run.ostrom.server.notifications.domain.ApprovalDelivery
import run.ostrom.server.notifications.domain.ApprovalRules
import run.ostrom.server.notifications.domain.DeliveryState
import run.ostrom.server.notifications.domain.Notification
import run.ostrom.server.notifications.domain.NotificationDelivery
import run.ostrom.server.notifications.domain.NotificationKind
import run.ostrom.server.notifications.domain.QueuedDelivery
import kotlin.uuid.Uuid

/**
 * Writes a new pending row of [delivery]: a member's notification keeps its wait and session, an approval its
 * request, gate and approver (kind [ApprovalRules.KIND]); the other's columns stay null.
 *
 * @param orgId The organization.
 * @param delivery The delivery.
 */
internal fun BatchInsertStatement.fill(orgId: Uuid, delivery: QueuedDelivery) {
    val t = NotificationDeliveries
    this[t.orgId] = orgId
    this[t.id] = delivery.id
    this[t.channel] = delivery.channel
    this[t.state] = DeliveryState.PENDING.wire
    this[t.attempts] = 0
    when (delivery) {
        is NotificationDelivery -> {
            val notification = delivery.notification
            this[t.eventSeq] = notification.eventSeq
            this[t.memberId] = notification.memberId
            this[t.kind] = notification.kind.wire
            this[t.title] = notification.title
            this[t.body] = notification.body
            this[t.sessionId] = notification.sessionId
            this[t.waitId] = notification.waitId
            this[t.collapseKey] = notification.collapseKey
            this[t.approvalId] = null
            this[t.gateId] = null
            this[t.approver] = null
            this[t.nextAttemptAt] = notification.createdAt
            this[t.leaseUntil] = notification.createdAt
            this[t.createdAt] = notification.createdAt
        }
        is ApprovalDelivery -> {
            this[t.eventSeq] = delivery.eventSeq
            this[t.memberId] = delivery.memberId
            this[t.kind] = ApprovalRules.KIND
            this[t.title] = delivery.title
            this[t.body] = delivery.body
            this[t.sessionId] = null
            this[t.waitId] = null
            this[t.collapseKey] = delivery.collapseKey
            this[t.approvalId] = delivery.approvalId
            this[t.gateId] = delivery.gateId
            this[t.approver] = delivery.approver
            this[t.nextAttemptAt] = delivery.createdAt
            this[t.leaseUntil] = delivery.createdAt
            this[t.createdAt] = delivery.createdAt
        }
    }
}

/**
 * @return The row's delivery, or null for a kind this server does not know or an approval missing what it names:
 *   never sent as another kind.
 */
internal fun ResultRow.toDelivery(): QueuedDelivery? {
    val kind = NotificationKind.parse(this[NotificationDeliveries.kind])
    return when {
        kind != null -> toNotification(kind)
        this[NotificationDeliveries.kind] == ApprovalRules.KIND -> toApproval()
        else -> null
    }
}

private fun ResultRow.toApproval(): ApprovalDelivery? {
    val t = NotificationDeliveries
    val approvalId = this[t.approvalId]
    val gateId = this[t.gateId]
    val approver = this[t.approver]
    if (approvalId == null || gateId == null || approver == null) return null
    return ApprovalDelivery(
        id = this[t.id],
        orgId = this[t.orgId],
        memberId = this[t.memberId],
        approver = approver,
        approvalId = approvalId,
        gateId = gateId,
        channel = this[t.channel],
        attempts = this[t.attempts],
        eventSeq = this[t.eventSeq],
        title = this[t.title],
        body = this[t.body],
        createdAt = this[t.createdAt],
    )
}

private fun ResultRow.toNotification(kind: NotificationKind): NotificationDelivery {
    val t = NotificationDeliveries
    val notification = Notification(
        id = this[t.id],
        orgId = this[t.orgId],
        memberId = this[t.memberId],
        kind = kind,
        title = this[t.title],
        body = this[t.body],
        sessionId = this[t.sessionId],
        waitId = this[t.waitId],
        collapseKey = this[t.collapseKey],
        createdAt = this[t.createdAt],
        eventSeq = this[t.eventSeq],
    )
    return NotificationDelivery(notification, this[t.channel], this[t.attempts])
}
