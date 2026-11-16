// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.infrastructure

import run.ostrom.server.usage.domain.HoldWindow
import run.ostrom.server.usage.domain.SpendHold
import run.ostrom.server.usage.domain.UsageEvent
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.statements.DeleteStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.UpsertStatement
import org.jetbrains.exposed.v1.core.statements.insertValue
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.statements.ReturningBlockingExecutable
import org.jetbrains.exposed.v1.jdbc.upsertReturning
import kotlin.uuid.Uuid

// The ledger's writes (UsageRepositoryImpl), one function each so that a test runs exactly what the repository sends.
// Every one filters on the org.

/**
 * Writes [events] in one statement (`INSERT … VALUES (…), (…)`): under the org's policy, a row of another org is
 * refused (WITH CHECK).
 */
internal fun JdbcTransaction.insertEvents(events: List<UsageEvent>) {
    UsageEvents.batchInsert(events, useMultiRowValues = true, shouldReturnGeneratedValues = false) { event ->
        this[UsageEvents.id] = event.id
        this[UsageEvents.orgId] = event.orgId
        this[UsageEvents.memberId] = event.memberId
        this[UsageEvents.kind] = event.kind.name
        this[UsageEvents.name] = event.name
        this[UsageEvents.provider] = event.provider
        this[UsageEvents.accountId] = event.accountId
        this[UsageEvents.sessionId] = event.sessionId
        this[UsageEvents.launchId] = event.launchId
        this[UsageEvents.workflowId] = event.workflowId
        this[UsageEvents.poolId] = event.poolId
        this[UsageEvents.inputTokens] = event.inputTokens
        this[UsageEvents.outputTokens] = event.outputTokens
        this[UsageEvents.cacheReadTokens] = event.cacheReadTokens
        this[UsageEvents.cacheWriteTokens] = event.cacheWriteTokens
        this[UsageEvents.costMicros] = event.costMicros
        this[UsageEvents.durationMs] = event.durationMillis
        this[UsageEvents.outcome] = event.outcome
        this[UsageEvents.at] = event.at
    }
}

/** The hold a recorded (or queued) call drops. */
internal fun StatementBuilder.holdDrop(orgId: Uuid, callId: Uuid): DeleteStatement =
    UsageHolds.deleteWhere { (this.orgId eq orgId) and (this.callId eq callId) }

/**
 * The org's holds past their retention. Until then an expired hold, whose call was never recorded (its replica lost
 * mid-call), stays charged.
 */
internal fun StatementBuilder.holdsRetire(orgId: Uuid, window: HoldWindow): DeleteStatement =
    UsageHolds.deleteWhere { (this.orgId eq orgId) and (heldAt less window.now - HoldWindow.RETENTION) }

/** A call's hold, replaced when it asks again: its amount and expiry, its first time kept. */
internal fun StatementBuilder.holdPut(hold: SpendHold, window: HoldWindow): UpsertStatement<Long> =
    UsageHolds.upsert(
        UsageHolds.callId,
        onUpdate = {
            it[UsageHolds.micros] = insertValue(UsageHolds.micros)
            it[UsageHolds.expiresAt] = insertValue(UsageHolds.expiresAt)
        },
    ) {
        it[callId] = hold.callId
        it[orgId] = hold.orgId
        it[memberId] = hold.memberId
        it[workflowId] = hold.workflowId
        it[launchId] = hold.launchId
        it[micros] = hold.micros
        it[expiresAt] = window.expiresAt
        it[heldAt] = window.now
    }

/** A member's past minutes, by the database's clock. */
internal fun StatementBuilder.ingestMinutesPurge(orgId: Uuid, memberId: Uuid): DeleteStatement =
    UsageIngestMinutes.deleteWhere {
        (this.orgId eq orgId) and (this.memberId eq memberId) and (minute less databaseMinute())
    }

/**
 * Counts [events] in the member's minute of the database's clock, only if it stays within [perMinute]: a row back when
 * counted, none when refused. The minute's row is locked until the transaction ends, so two batches of the member, on
 * any replica, count one after the other.
 */
internal fun ingestMinuteCount(orgId: Uuid, memberId: Uuid, events: Int, perMinute: Int): ReturningBlockingExecutable {
    val m = UsageIngestMinutes
    return m.upsertReturning(
        returning = listOf(m.events),
        onUpdate = { it[m.events] = m.events + insertValue(m.events) },
        where = { (m.events + insertValue(m.events)) lessEq perMinute },
    ) {
        it[this.orgId] = orgId
        it[this.memberId] = memberId
        it[minute] = databaseMinute()
        it[this.events] = events
    }
}
