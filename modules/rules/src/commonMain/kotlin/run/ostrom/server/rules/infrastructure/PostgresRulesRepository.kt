// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules.infrastructure

import run.ostrom.server.gates.domain.models.SelfApprovalPolicy
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.rowBefore
import run.ostrom.server.kernel.database.rowLess
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.rules.domain.models.Decision
import run.ostrom.server.rules.domain.models.DecisionQuery
import run.ostrom.server.rules.domain.models.Rule
import run.ostrom.server.rules.domain.models.RuleQuery
import run.ostrom.server.rules.domain.repositories.RulesRepository
import run.ostrom.server.rules.domain.repositories.RulesTransaction
import run.ostrom.server.rules.domain.services.RuleDocuments
import run.ostrom.server.rules.domain.services.SelfApprovalValues
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [RulesRepository] on PostgreSQL (tables of [RulesMigrations]). Every query runs in a transaction confined to one
 * organization (the serving role with the organization set, `withOrganization`), which row level security enforces,
 * and filters on `org_id` too.
 *
 * @param database The serving role's pool.
 */
class PostgresRulesRepository(private val database: JdbcDatabase) : RulesRepository {
    override suspend fun rules(orgId: Uuid, query: RuleQuery, limit: Int): List<Rule> =
        database.withOrganization(orgId) { rulesQuery(orgId, query, limit).map { it.toRule() } }

    override suspend fun rulesById(orgId: Uuid, after: String?, limit: Int): List<Rule> =
        database.withOrganization(orgId) { rulesByIdQuery(orgId, after, limit).map { it.toRule() } }

    override suspend fun gatedRules(orgId: Uuid, after: String?, limit: Int): List<Rule> =
        database.withOrganization(orgId) { gatedRulesQuery(orgId, after, limit).map { it.toRule() } }

    override suspend fun lastRuleDecisions(orgId: Uuid): List<Decision> = database.withOrganization(orgId) {
        val d = RulesDecisions
        d.select(d.columns)
            .withDistinctOn(d.subjectId)
            .where { (d.orgId eq orgId) and (d.subjectKind eq RULE) }
            .orderBy(d.subjectId to SortOrder.ASC, d.decidedAt to SortOrder.DESC, d.id to SortOrder.DESC)
            .map { it.toDecision() }
    }

    override suspend fun rule(orgId: Uuid, id: String): Rule? = database.withOrganization(orgId) {
        selectRule(orgId, id, lock = false)
    }

    override suspend fun decisions(orgId: Uuid, query: DecisionQuery): List<Decision> =
        database.withOrganization(orgId) {
            val d = RulesDecisions
            d.select(d.columns)
                .where {
                    var filters: Op<Boolean> = d.orgId eq orgId
                    query.subjectKind?.let { filters = filters and (d.subjectKind eq it) }
                    query.subjectId?.let { filters = filters and (d.subjectId eq it) }
                    query.before?.let { before ->
                        val cursor = d.select(d.decidedAt, d.id).where { (d.orgId eq orgId) and (d.id eq before) }
                        filters = filters and rowBefore(listOf(d.decidedAt, d.id), cursor)
                    }
                    filters
                }
                .orderBy(d.decidedAt to SortOrder.DESC, d.id to SortOrder.DESC)
                .limit(query.limit)
                .map { it.toDecision() }
        }

    override suspend fun decision(orgId: Uuid, id: String): Decision? = database.withOrganization(orgId) {
        val d = RulesDecisions
        d.select(d.columns).where { (d.orgId eq orgId) and (d.id eq id) }.singleOrNull()?.toDecision()
    }

    override suspend fun markSigned(orgId: Uuid, ids: List<String>) {
        if (ids.isEmpty()) return
        database.withOrganization(orgId) {
            val d = RulesDecisions
            d.update({ (d.orgId eq orgId) and (d.id inList ids) }) { it[signed] = true }
        }
    }

    override suspend fun selfApproval(orgId: Uuid): SelfApprovalPolicy? = database.withOrganization(orgId) {
        selectSelfApproval(orgId, lock = false)
    }

    override suspend fun <T> transaction(orgId: Uuid, block: RulesTransaction.() -> T): T =
        database.withOrganization(orgId) { PostgresRulesTransaction(this, orgId).block() }

    private class PostgresRulesTransaction(private val tx: JdbcTransaction, private val org: Uuid) :
        RulesTransaction {
        override fun lockRule(id: String): Rule? = tx.selectRule(org, id, lock = true)

        override fun insertRule(rule: Rule) {
            RulesRules.insert { it.rule(org, rule) }
        }

        override fun updateRule(rule: Rule) {
            val r = RulesRules
            r.update({ (r.orgId eq org) and (r.id eq rule.id) }) { it.rule(org, rule) }
        }

        override fun deleteRule(id: String) {
            RulesRules.deleteWhere { (RulesRules.orgId eq org) and (RulesRules.id eq id) }
        }

        override fun insertDecision(decision: Decision) {
            RulesDecisions.insert {
                it[this.orgId] = org
                it[id] = decision.id
                it[decidedAt] = decision.at
                it[decidedBy] = decision.by
                it[subjectKind] = decision.subject.kind
                it[subjectId] = decision.subject.id
                it[subjectLabel] = decision.subject.label
                it[action] = decision.action
                it[beforeDoc] = decision.before?.toString()
                it[afterDoc] = decision.after?.toString()
                it[ruleDigest] = decision.ruleDigest
                it[signed] = decision.signed
            }
        }

        override fun lockSelfApproval(): SelfApprovalPolicy? = tx.selectSelfApproval(org, lock = true)

        override fun putSelfApproval(policy: SelfApprovalPolicy, at: Instant) {
            RulesGatePolicy.upsert(RulesGatePolicy.orgId) {
                it[this.orgId] = org
                it[selfApproval] = SelfApprovalValues.valueOf(policy)
                it[updatedAt] = at
            }
        }
    }

    /**
     * Whether a rule covers a repository: its scope is empty, names it, or names a path under it, case ignored as
     * GitHub names repositories; the scope is the JSON array the contract writes.
     */
    private class Covers(private val repo: String) : Op<Boolean>() {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
            val scope = RulesRules.scope
            append("(jsonb_array_length(CAST(")
            append(scope)
            append(" AS jsonb)) = 0 OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(CAST(")
            append(scope)
            append(" AS jsonb)) AS entry(name) ")
            append("WHERE lower(entry.name) = ")
            registerArgument(TextColumnType(), repo)
            append(" OR starts_with(lower(entry.name), ")
            registerArgument(TextColumnType(), "$repo/")
            append(")))")
        }
    }

    internal companion object {
        const val RULE = "rule"

        /**
         * The rules of an organization a query lists, in the order of the console (group, then id), after its cursor,
         * at most [limit]. Every filter is in the SQL, before the limit: a repository's rules are never cut by the
         * others' (audit of the reads, B2). The repository is compared lowercased ([Covers]).
         *
         * @param orgId The organization.
         * @param query The filters.
         * @param limit The most rules.
         * @return The read.
         */
        fun rulesQuery(orgId: Uuid, query: RuleQuery, limit: Int): Query {
            val r = RulesRules
            return r.select(r.columns)
                .where {
                    var filters: Op<Boolean> = r.orgId eq orgId
                    query.status?.let { filters = filters and (r.status eq it.value) }
                    query.group?.let { filters = filters and (r.group eq it) }
                    query.repo?.let { filters = filters and Covers(it.lowercase()) }
                    // The list's own order, on the index `rules_rules_by_group`.
                    query.after?.let { after ->
                        filters = filters and rowLess(listOf(text(after.group), text(after.id)), listOf(r.group, r.id))
                    }
                    filters
                }
                .orderBy(r.group to SortOrder.ASC, r.id to SortOrder.ASC)
                .limit(limit)
        }

        /**
         * A page of every rule, by id, on the primary key.
         *
         * @param orgId The organization.
         * @param after The id the page starts after, if any.
         * @param limit The most rules.
         * @return The read.
         */
        fun rulesByIdQuery(orgId: Uuid, after: String?, limit: Int): Query = page(orgId, after, limit) { it }

        /**
         * A page of the gated rules, by id, on the partial index `rules_rules_gated`.
         *
         * @param orgId The organization.
         * @param after The id the page starts after, if any.
         * @param limit The most rules.
         * @return The read.
         */
        fun gatedRulesQuery(orgId: Uuid, after: String?, limit: Int): Query = page(orgId, after, limit) {
            it and RulesRules.gate.isNotNull() and (RulesRules.status neq REJECTED)
        }

        private const val REJECTED = "rejected"

        private fun page(orgId: Uuid, after: String?, limit: Int, filter: (Op<Boolean>) -> Op<Boolean>): Query {
            val r = RulesRules
            return r.select(r.columns)
                .where {
                    val mine = filter(r.orgId eq orgId)
                    if (after == null) mine else mine and (r.id greater after)
                }
                .orderBy(r.id to SortOrder.ASC)
                .limit(limit)
        }

        private fun text(value: String) = QueryParameter(value, TextColumnType())

        fun JdbcTransaction.selectRule(orgId: Uuid, id: String, lock: Boolean): Rule? {
            val r = RulesRules
            val query = r.select(r.columns).where { (r.orgId eq orgId) and (r.id eq id) }
            return (if (lock) query.forUpdate() else query).singleOrNull()?.toRule()
        }

        fun JdbcTransaction.selectSelfApproval(orgId: Uuid, lock: Boolean): SelfApprovalPolicy? {
            val p = RulesGatePolicy
            val query = p.select(p.selfApproval).where { p.orgId eq orgId }
            val row = (if (lock) query.forUpdate() else query).singleOrNull()
            return row?.let { SelfApprovalValues.of(it[p.selfApproval]) }
        }

        fun UpdateBuilder<*>.rule(orgId: Uuid, rule: Rule) {
            val r = RulesRules
            this[r.orgId] = orgId
            this[r.id] = rule.id
            this[r.statement] = rule.statement
            this[r.output] = rule.output
            this[r.group] = rule.group
            this[r.scope] = texts(rule.scope).toString()
            this[r.sources] = RuleDocuments.full(rule).getValue("sources").toString()
            this[r.why] = rule.why
            this[r.strength] = rule.strength.value
            this[r.confidence] = rule.confidence
            this[r.status] = rule.status.value
            this[r.foundBy] = rule.foundBy
            this[r.foundAt] = rule.foundAt
            this[r.reviewBy] = rule.reviewBy
            this[r.decidedBy] = rule.decidedBy
            this[r.decidedAt] = rule.decidedAt
            this[r.origin] = rule.origin
            this[r.gate] = rule.gate?.let { RuleDocuments.gate(it).toString() }
            this[r.updatedAt] = rule.updatedAt
        }

        fun texts(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))
    }
}
