// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.fakes

import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.rules.domain.models.Decider
import dev.ostrom.server.rules.domain.models.Decision
import dev.ostrom.server.rules.domain.models.DecisionQuery
import dev.ostrom.server.rules.domain.models.Rule
import dev.ostrom.server.rules.domain.models.RuleQuery
import dev.ostrom.server.rules.domain.models.RuleStatus
import dev.ostrom.server.rules.domain.models.RuleStrength
import dev.ostrom.server.rules.domain.repositories.RulesRepository
import dev.ostrom.server.rules.domain.repositories.RulesTransaction
import dev.ostrom.server.rules.domain.services.DecisionSigner
import dev.ostrom.server.rules.domain.usecases.SignDecisionsUseCaseImpl
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The organizations and people of the unit tests. */
object RulesFixtures {
    val orgA: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    val orgB: Uuid = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    val lyes = Decider(orgA, "lyes")
    val bob = Decider(orgB, "bob")

    /** 30 October 2026, 12:00 UTC. */
    val epoch: Instant = Instant.parse("2026-10-30T12:00:00Z")

    /**
     * A rule as the analysis would have found it.
     *
     * @param id Its id.
     * @param orgId Its organization.
     * @param status Its status.
     * @param group Its group.
     * @param scope Its scope.
     */
    fun rule(
        id: String = "rule_1",
        orgId: Uuid = orgA,
        status: RuleStatus = RuleStatus.PROPOSED,
        group: String = "Shipping",
        scope: List<String> = emptyList(),
    ) = Rule(
        orgId = orgId,
        id = id,
        statement = "Every change to main goes through a pull request.",
        output = "rule",
        group = group,
        scope = scope,
        sources = emptyList(),
        why = null,
        strength = RuleStrength.REQUIRED,
        confidence = 0.8,
        status = status,
        foundBy = "analysis",
        foundAt = epoch,
        reviewBy = null,
        decidedBy = null,
        decidedAt = null,
        origin = "learned",
        gate = null,
        updatedAt = epoch,
    )
}

/** A clock the test moves by hand. */
class TestClock(var now: Instant = RulesFixtures.epoch) : Clock {
    override fun now(): Instant = now

    fun advance(by: Duration) {
        now += by
    }
}

/** Keeps every log event. */
class RecordingLogger : Logger {
    val events = mutableListOf<Pair<LogLevel, String>>()

    override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
        events += level to message
    }

    override fun with(fields: Map<String, Any?>): Logger = this
}

/** Signs nothing: records what it was asked, and fails while [failing]. */
class FakeDecisionSigner : DecisionSigner {
    val signed = mutableListOf<Decision>()
    var failing = false

    override fun digestOf(document: JsonObject): String = "sha256:" + document.toString().length

    override suspend fun sign(decision: Decision) {
        if (failing) error("receipts unavailable")
        signed += decision
    }
}

/** Rules, decisions and policies in memory, one map per organization, as row level security confines them. */
class FakeRulesRepository : RulesRepository {
    val rules = mutableMapOf<Pair<Uuid, String>, Rule>()
    val decisions = mutableListOf<Decision>()
    val policies = mutableMapOf<Uuid, SelfApprovalPolicy>()
    val limits = mutableListOf<Int>()
    var transactions = 0

    override suspend fun rules(orgId: Uuid, query: RuleQuery, limit: Int): List<Rule> {
        limits += limit
        return rules.values.filter { it.orgId == orgId }.sortedBy { it.group + it.id }.take(limit)
    }

    override suspend fun rule(orgId: Uuid, id: String): Rule? = rules[orgId to id]

    override suspend fun decisions(orgId: Uuid, query: DecisionQuery): List<Decision> =
        decisions.filter { it.orgId == orgId }.reversed().take(query.limit)

    override suspend fun markSigned(orgId: Uuid, ids: List<String>) {
        decisions.replaceAll { if (it.orgId == orgId && it.id in ids) it.copy(signed = true) else it }
    }

    override suspend fun selfApproval(orgId: Uuid): SelfApprovalPolicy? = policies[orgId]

    override suspend fun <T> transaction(orgId: Uuid, block: suspend RulesTransaction.() -> T): T {
        transactions++
        return Transaction(orgId).block()
    }

    private inner class Transaction(private val orgId: Uuid) : RulesTransaction {
        override suspend fun lockRule(id: String): Rule? = rules[orgId to id]

        override suspend fun insertRule(rule: Rule) {
            check(rule.orgId == orgId)
            rules[orgId to rule.id] = rule
        }

        override suspend fun updateRule(rule: Rule) {
            check(rule.orgId == orgId)
            rules[orgId to rule.id] = rule
        }

        override suspend fun deleteRule(id: String) {
            rules.remove(orgId to id)
        }

        override suspend fun insertDecision(decision: Decision) {
            check(decision.orgId == orgId)
            decisions += decision
        }

        override suspend fun lockSelfApproval(): SelfApprovalPolicy? = policies[orgId]

        override suspend fun putSelfApproval(policy: SelfApprovalPolicy, at: Instant) {
            policies[orgId] = policy
        }
    }
}

/**
 * The use cases' collaborators, wired together.
 *
 * @property repository The rules in memory.
 * @property signer The receipts.
 * @property logger The logs.
 * @property clock The time.
 */
class RulesBench(
    val repository: FakeRulesRepository = FakeRulesRepository(),
    val signer: FakeDecisionSigner = FakeDecisionSigner(),
    val logger: RecordingLogger = RecordingLogger(),
    val clock: TestClock = TestClock(),
) {
    val signDecisions = SignDecisionsUseCaseImpl(repository, signer, logger)
}
