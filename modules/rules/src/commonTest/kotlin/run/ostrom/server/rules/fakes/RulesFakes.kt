// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules.fakes

import run.ostrom.server.gates.domain.models.SelfApprovalPolicy
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.rules.domain.models.Decider
import run.ostrom.server.rules.domain.models.Decision
import run.ostrom.server.rules.domain.models.DecisionQuery
import run.ostrom.server.rules.domain.models.Rule
import run.ostrom.server.rules.domain.models.RuleQuery
import run.ostrom.server.rules.domain.models.RuleStatus
import run.ostrom.server.rules.domain.models.RuleStrength
import run.ostrom.server.rules.domain.repositories.RulesRepository
import run.ostrom.server.rules.domain.repositories.RulesTransaction
import run.ostrom.server.rules.domain.services.DecisionSigner
import run.ostrom.server.rules.domain.usecases.SignDecisionsUseCaseImpl
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The organizations and people of the unit tests. */
object RulesFixtures {
    val orgA: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    val orgB: Uuid = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    val lyes = Decider(orgA, "lyes", person("0000000a-0000-0000-0000-0000000000a1"))
    val bob = Decider(orgB, "bob", person("0000000b-0000-0000-0000-0000000000a1"))

    /**
     * A person signed in to the console.
     *
     * @param id Their user id.
     * @return Their credential.
     */
    fun person(id: String): AuthenticatedPrincipal =
        AuthenticatedPrincipal(id, PrincipalKind.USER, setOf("user:*"), channel = PrincipalChannel.BROWSER)

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
        val after = query.after
        return rules.values.filter { it.orgId == orgId }
            .sortedWith(compareBy<Rule>({ it.group }, { it.id }))
            .filter { after == null || it.group > after.group || (it.group == after.group && it.id > after.id) }
            .take(limit)
    }

    override suspend fun rulesById(orgId: Uuid, after: String?, limit: Int): List<Rule> = rules.values
        .filter { it.orgId == orgId && (after == null || it.id > after) }
        .sortedBy { it.id }
        .take(limit)

    override suspend fun gatedRules(orgId: Uuid, after: String?, limit: Int): List<Rule> =
        rulesById(orgId, after, Int.MAX_VALUE)
            .filter { it.gate != null && it.status != RuleStatus.REJECTED }
            .take(limit)

    override suspend fun lastRuleDecisions(orgId: Uuid): List<Decision> = decisions
        .filter { it.orgId == orgId && it.subject.kind == "rule" }
        .groupBy { it.subject.id }
        .map { (_, all) -> all.maxWith(compareBy<Decision> { it.at }.thenBy { it.id }) }

    override suspend fun rule(orgId: Uuid, id: String): Rule? = rules[orgId to id]

    // As the database: after `before`, older decisions only; none after a decision it does not hold.
    override suspend fun decisions(orgId: Uuid, query: DecisionQuery): List<Decision> {
        val newest = decisions.filter { it.orgId == orgId }.reversed()
        val start = query.before?.let { before -> newest.indexOfFirst { it.id == before } + 1 } ?: 0
        return if (start == 0 && query.before != null) emptyList() else newest.drop(start).take(query.limit)
    }

    override suspend fun decision(orgId: Uuid, id: String): Decision? =
        decisions.firstOrNull { it.orgId == orgId && it.id == id }

    override suspend fun markSigned(orgId: Uuid, ids: List<String>) {
        for (index in decisions.indices) {
            val decision = decisions[index]
            if (decision.orgId == orgId && decision.id in ids) decisions[index] = decision.copy(signed = true)
        }
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
 * @property audit The organization's audit log.
 */
class RulesBench(
    val repository: FakeRulesRepository = FakeRulesRepository(),
    val signer: FakeDecisionSigner = FakeDecisionSigner(),
    val logger: RecordingLogger = RecordingLogger(),
    val clock: TestClock = TestClock(),
    val audit: RecordingAuditTrail = RecordingAuditTrail(),
) {
    val signDecisions = SignDecisionsUseCaseImpl(repository, signer, logger)
}

/** An audit log in memory: the lines written, in order. */
class RecordingAuditTrail : OrgAuditTrail {
    val records = mutableListOf<OrgAuditRecord>()

    override suspend fun record(record: OrgAuditRecord) {
        records += record
    }
}
