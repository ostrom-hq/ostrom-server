// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.governance.fakes

import dev.ostrom.server.governance.domain.Confirmation
import dev.ostrom.server.governance.domain.ConfirmationAudit
import dev.ostrom.server.governance.domain.ConfirmationError
import dev.ostrom.server.governance.domain.ConfirmationPerson
import dev.ostrom.server.governance.domain.ConfirmationStatus
import dev.ostrom.server.governance.domain.ConfirmationStep
import dev.ostrom.server.governance.domain.ConfirmationWaits
import dev.ostrom.server.governance.domain.ConfirmationsRepository
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.GovernanceChange
import dev.ostrom.server.kernel.auth.GovernanceConfirmer
import dev.ostrom.server.kernel.auth.GovernancePreview
import dev.ostrom.server.kernel.auth.GovernanceWrite
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.errors.OstromException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A clock the test moves. */
class TestClock(var now: Instant = Instant.parse("2026-11-02T20:00:00Z")) : Clock {
    override fun now(): Instant = now
}

/** The confirmations in memory, with the SQL's semantics (an answer changes a pending, unexpired one once). */
class InMemoryConfirmations : ConfirmationsRepository {
    val rows: MutableMap<String, Confirmation> = linkedMapOf()

    /** When set, the next answer finds the confirmation taken by another instance. */
    var loseNextAnswer: Boolean = false

    /** What happens meanwhile, when an answer is lost. */
    var whileLosing: () -> Unit = {}

    override suspend fun insert(confirmation: Confirmation) {
        rows[confirmation.id] = confirmation
    }

    override suspend fun get(orgId: Uuid, id: String): Confirmation? = rows[id]?.takeIf { it.orgId == orgId }

    override suspend fun waiting(orgId: Uuid, userId: String, now: Instant): List<Confirmation> = rows.values.filter {
        it.orgId == orgId && it.requestedBy.userId == userId && it.status == ConfirmationStatus.PENDING &&
            it.expiresAt > now
    }

    override suspend fun expire(orgId: Uuid, now: Instant): List<Confirmation> = rows.values
        .filter { it.orgId == orgId && it.status == ConfirmationStatus.PENDING && it.expiresAt <= now }
        .map { it.copy(status = ConfirmationStatus.EXPIRED).also { expired -> rows[it.id] = expired } }

    override suspend fun answer(
        orgId: Uuid,
        id: String,
        status: ConfirmationStatus,
        by: ConfirmationPerson,
        at: Instant,
        error: ConfirmationError?,
    ): Boolean {
        if (loseNextAnswer) {
            loseNextAnswer = false
            whileLosing()
            return false
        }
        val row = get(orgId, id)?.takeIf { it.status == ConfirmationStatus.PENDING && it.expiresAt > at }
            ?: return false
        rows[id] = row.copy(status = status, answeredBy = by, answeredAt = at, error = error)
        return true
    }

    override suspend fun settle(orgId: Uuid, id: String, result: JsonElement?, error: ConfirmationError?) {
        val row = rows.getValue(id)
        rows[id] = if (error == null) {
            row.copy(result = result)
        } else {
            row.copy(status = ConfirmationStatus.FAILED, error = error)
        }
    }

    override suspend fun organizationsWithExpired(now: Instant, limit: Int): List<Uuid> = rows.values
        .filter { it.status == ConfirmationStatus.PENDING && it.expiresAt <= now }
        .map { it.orgId }.distinct().take(limit)
}

/** Keeps each step, as `step id`. */
class RecordingAudit : ConfirmationAudit {
    val steps: MutableList<String> = mutableListOf()
    val actors: MutableList<String?> = mutableListOf()
    var failWith: Exception? = null

    override suspend fun record(confirmation: Confirmation, step: ConfirmationStep, actorUserId: String?) {
        failWith?.let { throw it }
        steps += "${step.name} ${confirmation.id}"
        actors += actorUserId
    }
}

/** Memberships by subject: their organization and role. */
class FakeMembers(private val members: Map<String, Pair<Uuid, OrgRole>>) : PrincipalOrgResolver {
    val memberIds: MutableMap<String, Uuid> = mutableMapOf()

    override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
        members[principal.subjectId]?.let { (org, role) ->
            OrgPrincipal(org, memberIds.getOrPut(principal.subjectId) { Uuid.random() }, role)
        }
}

/**
 * One setting a governance write changes, `{"to": value}`: what it replaces is the setting in force.
 *
 * @param kinds The kinds it owns.
 */
class FakeChange(override val kinds: Set<String> = setOf(KIND)) : GovernanceChange {
    var current: JsonElement? = JsonPrimitive("sole-approver")
    var refusePreview: OstromException? = null
    var refuseApply: Exception? = null
    val confirmers: MutableList<GovernanceConfirmer> = mutableListOf()

    override suspend fun preview(orgId: Uuid, write: GovernanceWrite): GovernancePreview {
        refusePreview?.let { throw it }
        return GovernancePreview("Set it", current, write.payload["to"])
    }

    override suspend fun apply(orgId: Uuid, write: GovernanceWrite, confirmer: GovernanceConfirmer): JsonElement? {
        refuseApply?.let { throw it }
        confirmers += confirmer
        current = write.payload["to"]
        return current
    }

    companion object {
        const val KIND = "gates.policy"
    }
}

/** Waits by moving the clock: a wait ends at once, as if nothing happened meanwhile, unless [during] answers. */
class ClockWaits(private val clock: TestClock) : ConfirmationWaits {
    val changed: MutableList<String> = mutableListOf()
    var waited: Duration = Duration.ZERO
    var during: (suspend () -> Unit)? = null

    override suspend fun await(id: String, within: Duration) {
        waited += within
        clock.now += within
        during?.invoke()
    }

    override fun changed(id: String) {
        changed += id
    }
}
