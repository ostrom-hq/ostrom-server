// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.governance.fakes

import run.ostrom.server.governance.domain.Confirmation
import run.ostrom.server.governance.domain.ConfirmationAudit
import run.ostrom.server.governance.domain.ConfirmationError
import run.ostrom.server.governance.domain.ConfirmationOutcome
import run.ostrom.server.governance.domain.ConfirmationStatus
import run.ostrom.server.governance.domain.ConfirmationStep
import run.ostrom.server.governance.domain.ConfirmationWaits
import run.ostrom.server.governance.domain.ConfirmationsRepository
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.GovernanceChange
import run.ostrom.server.kernel.auth.GovernanceConfirmer
import run.ostrom.server.kernel.auth.GovernancePreview
import run.ostrom.server.kernel.auth.GovernanceWrite
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.errors.OstromException
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

    override suspend fun answer(orgId: Uuid, id: String, outcome: ConfirmationOutcome): Boolean {
        val lost = loseNextAnswer
        if (lost) {
            loseNextAnswer = false
            whileLosing()
        }
        val row = get(orgId, id)?.takeIf {
            !lost && it.status == ConfirmationStatus.PENDING && it.expiresAt > outcome.at
        }
        row?.let {
            rows[id] = it.copy(
                status = outcome.status,
                answeredBy = outcome.by,
                answeredAt = outcome.at,
                error = outcome.error,
            )
        }
        return row != null
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

    /** When set, the result holds a secret, shown once: without it, it reads `shown`. */
    var secret: Boolean = false

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

    override fun shownOnce(kind: String, result: JsonElement): JsonElement? =
        if (secret) JsonPrimitive("shown") else super.shownOnce(kind, result)

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
