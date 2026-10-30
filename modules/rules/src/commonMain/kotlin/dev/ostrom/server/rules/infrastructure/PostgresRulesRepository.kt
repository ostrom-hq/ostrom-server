// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.infrastructure

import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.kernel.database.withOrganization
import dev.ostrom.server.rules.domain.models.Decision
import dev.ostrom.server.rules.domain.models.DecisionQuery
import dev.ostrom.server.rules.domain.models.Rule
import dev.ostrom.server.rules.domain.models.RuleQuery
import dev.ostrom.server.rules.domain.repositories.RulesRepository
import dev.ostrom.server.rules.domain.repositories.RulesTransaction
import dev.ostrom.server.rules.domain.services.RuleDocuments
import dev.ostrom.server.rules.domain.services.SelfApprovalValues
import dev.ostrom.server.rules.domain.usecases.ListRulesUseCase
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [RulesRepository] on PostgreSQL (tables of [RulesMigrations]). Every query runs in a transaction confined to one
 * organization (`withOrganization`), which row level security enforces.
 *
 * @param driver The connection pool.
 */
class PostgresRulesRepository(private val driver: Driver) : RulesRepository {
    override suspend fun rules(orgId: Uuid, query: RuleQuery, limit: Int): List<Rule> =
        driver.withOrganization(orgId) { tx ->
            val filters = buildString {
                append("org_id = :org")
                if (query.status != null) append(" AND status = :status")
                if (query.group != null) append(" AND rule_group = :group")
            }
            val statement = Statement.create(
                "SELECT $RULE_COLUMNS FROM rules_rules WHERE $filters ORDER BY rule_group, id LIMIT :limit",
            ).bind("org", orgId).bind("limit", limit)
            // A named parameter is bound only when the SQL names it: sqlx4k refuses an unknown one.
            query.status?.let { statement.bind("status", it.value) }
            query.group?.let { statement.bind("group", it) }
            // The scope is JSON: a repository filter reads it here, on a set bounded by `limit`.
            tx.fetchAll(statement).getOrThrow().map { it.toRule() }.filter { covers(it, query.repo) }
        }

    override suspend fun gatedRules(orgId: Uuid): List<Rule> = driver.withOrganization(orgId) { tx ->
        tx.fetchAll(
            Statement.create(
                "SELECT $RULE_COLUMNS FROM rules_rules WHERE org_id = :org AND gate IS NOT NULL " +
                    "AND status <> 'rejected' ORDER BY id LIMIT :limit",
            ).bind("org", orgId).bind("limit", ListRulesUseCase.MAX_RULES),
        ).getOrThrow().map { it.toRule() }
    }

    override suspend fun lastRuleDecisions(orgId: Uuid): List<Decision> = driver.withOrganization(orgId) { tx ->
        tx.fetchAll(
            Statement.create(
                "SELECT DISTINCT ON (subject_id) $DECISION_COLUMNS FROM rules_decisions " +
                    "WHERE org_id = :org AND subject_kind = 'rule' ORDER BY subject_id, decided_at DESC, id DESC",
            ).bind("org", orgId),
        ).getOrThrow().map { it.toDecision() }
    }

    override suspend fun rule(orgId: Uuid, id: String): Rule? = driver.withOrganization(orgId) { tx ->
        tx.selectRule(orgId, id, lock = false)
    }

    override suspend fun decisions(orgId: Uuid, query: DecisionQuery): List<Decision> =
        driver.withOrganization(orgId) { tx ->
            val filters = buildString {
                append("org_id = :org")
                if (query.subjectKind != null) append(" AND subject_kind = :kind")
                if (query.subjectId != null) append(" AND subject_id = :subject")
                if (query.before != null) {
                    append(
                        " AND (decided_at, id) < (SELECT decided_at, id FROM rules_decisions " +
                            "WHERE org_id = :org AND id = :before)",
                    )
                }
            }
            val statement = Statement.create(
                "SELECT $DECISION_COLUMNS FROM rules_decisions WHERE $filters " +
                    "ORDER BY decided_at DESC, id DESC LIMIT :limit",
            ).bind("org", orgId).bind("limit", query.limit)
            query.subjectKind?.let { statement.bind("kind", it) }
            query.subjectId?.let { statement.bind("subject", it) }
            query.before?.let { statement.bind("before", it) }
            tx.fetchAll(statement).getOrThrow().map { it.toDecision() }
        }

    override suspend fun markSigned(orgId: Uuid, ids: List<String>) {
        if (ids.isEmpty()) return
        driver.withOrganization(orgId) { tx ->
            val names = ids.indices.joinToString(", ") { ":id$it" }
            val statement = Statement.create(
                "UPDATE rules_decisions SET signed = TRUE WHERE org_id = :org AND id IN ($names)",
            ).bind("org", orgId)
            ids.forEachIndexed { index, id -> statement.bind("id$index", id) }
            tx.execute(statement).getOrThrow()
        }
    }

    override suspend fun selfApproval(orgId: Uuid): SelfApprovalPolicy? = driver.withOrganization(orgId) { tx ->
        tx.selectSelfApproval(orgId, lock = false)
    }

    override suspend fun <T> transaction(orgId: Uuid, block: suspend RulesTransaction.() -> T): T =
        driver.withOrganization(orgId) { tx -> PostgresRulesTransaction(tx, orgId).block() }

    private class PostgresRulesTransaction(private val tx: QueryExecutor, private val orgId: Uuid) : RulesTransaction {
        override suspend fun lockRule(id: String): Rule? = tx.selectRule(orgId, id, lock = true)

        override suspend fun insertRule(rule: Rule) {
            tx.execute(
                Statement.create(
                    "INSERT INTO rules_rules ($RULE_COLUMNS) VALUES (:org, :id, :statement, :output, :group, " +
                        ":scope, :sources, :why, :strength, :confidence, :status, :found_by, :found_at, " +
                        ":review_by, :decided_by, :decided_at, :origin, :gate, :updated_at)",
                ).bindRule(rule),
            ).getOrThrow()
        }

        override suspend fun updateRule(rule: Rule) {
            tx.execute(
                Statement.create(
                    "UPDATE rules_rules SET statement = :statement, output = :output, rule_group = :group, " +
                        "scope = :scope, sources = :sources, why = :why, strength = :strength, " +
                        "confidence = :confidence, status = :status, found_by = :found_by, found_at = :found_at, " +
                        "review_by = :review_by, decided_by = :decided_by, decided_at = :decided_at, " +
                        "origin = :origin, gate = :gate, updated_at = :updated_at WHERE org_id = :org AND id = :id",
                ).bindRule(rule),
            ).getOrThrow()
        }

        override suspend fun deleteRule(id: String) {
            tx.execute(
                Statement.create("DELETE FROM rules_rules WHERE org_id = :org AND id = :id")
                    .bind("org", orgId)
                    .bind("id", id),
            ).getOrThrow()
        }

        override suspend fun insertDecision(decision: Decision) {
            tx.execute(
                Statement.create(
                    "INSERT INTO rules_decisions ($DECISION_COLUMNS) VALUES (:org, :id, :at, :by, :kind, " +
                        ":subject, :label, :action, :before, :after, :digest, :signed)",
                )
                    .bind("org", orgId)
                    .bind("id", decision.id)
                    .bind("at", decision.at)
                    .bind("by", decision.by)
                    .bind("kind", decision.subject.kind)
                    .bind("subject", decision.subject.id)
                    .bind("label", decision.subject.label)
                    .bind("action", decision.action)
                    .bind("before", decision.before?.toString())
                    .bind("after", decision.after?.toString())
                    .bind("digest", decision.ruleDigest)
                    .bind("signed", decision.signed),
            ).getOrThrow()
        }

        override suspend fun lockSelfApproval(): SelfApprovalPolicy? = tx.selectSelfApproval(orgId, lock = true)

        override suspend fun putSelfApproval(policy: SelfApprovalPolicy, at: Instant) {
            tx.execute(
                Statement.create(
                    "INSERT INTO rules_gate_policy (org_id, self_approval, updated_at) VALUES (:org, :policy, :at) " +
                        "ON CONFLICT (org_id) DO UPDATE SET self_approval = :policy, updated_at = :at",
                )
                    .bind("org", orgId)
                    .bind("policy", SelfApprovalValues.valueOf(policy))
                    .bind("at", at),
            ).getOrThrow()
        }

        private fun Statement.bindRule(rule: Rule): Statement = bind("org", orgId)
            .bind("id", rule.id)
            .bind("statement", rule.statement)
            .bind("output", rule.output)
            .bind("group", rule.group)
            .bind("scope", texts(rule.scope).toString())
            .bind("sources", RuleDocuments.full(rule).getValue("sources").toString())
            .bind("why", rule.why)
            .bind("strength", rule.strength.value)
            .bind("confidence", rule.confidence)
            .bind("status", rule.status.value)
            .bind("found_by", rule.foundBy)
            .bind("found_at", rule.foundAt)
            .bind("review_by", rule.reviewBy)
            .bind("decided_by", rule.decidedBy)
            .bind("decided_at", rule.decidedAt)
            .bind("origin", rule.origin)
            .bind("gate", rule.gate?.let { RuleDocuments.gate(it).toString() })
            .bind("updated_at", rule.updatedAt)
    }

    private companion object {
        const val RULE_COLUMNS = "org_id, id, statement, output, rule_group, scope, sources, why, strength, " +
            "confidence, status, found_by, found_at, review_by, decided_by, decided_at, origin, gate, updated_at"
        const val DECISION_COLUMNS = "org_id, id, decided_at, decided_by, subject_kind, subject_id, subject_label, " +
            "action, before_doc, after_doc, rule_digest, signed"

        suspend fun QueryExecutor.selectRule(orgId: Uuid, id: String, lock: Boolean): Rule? = fetchAll(
            Statement.create(
                "SELECT $RULE_COLUMNS FROM rules_rules WHERE org_id = :org AND id = :id" +
                    if (lock) " FOR UPDATE" else "",
            ).bind("org", orgId).bind("id", id),
        ).getOrThrow().firstOrNull()?.toRule()

        suspend fun QueryExecutor.selectSelfApproval(orgId: Uuid, lock: Boolean): SelfApprovalPolicy? = fetchAll(
            Statement.create(
                "SELECT self_approval FROM rules_gate_policy WHERE org_id = :org" + if (lock) " FOR UPDATE" else "",
            ).bind("org", orgId),
        ).getOrThrow().firstOrNull()?.let { SelfApprovalValues.of(it.get("self_approval").asString()) }

        // Whole names or a path under them, case ignored, as GitHub names repositories.
        fun covers(rule: Rule, repo: String?): Boolean {
            val name = repo?.lowercase() ?: return true
            return rule.scope.isEmpty() || rule.scope.any { entry ->
                val scoped = entry.lowercase()
                scoped == name || scoped.startsWith("$name/")
            }
        }

        fun texts(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))
    }
}
