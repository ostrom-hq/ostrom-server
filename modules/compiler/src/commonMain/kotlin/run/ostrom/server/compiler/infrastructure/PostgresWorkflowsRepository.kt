// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.infrastructure

import run.ostrom.server.compiler.domain.models.Authorship
import run.ostrom.server.compiler.domain.models.DecisionDoc
import run.ostrom.server.compiler.domain.models.StoredWorkflow
import run.ostrom.server.compiler.domain.repositories.LockedWorkflows
import run.ostrom.server.compiler.domain.repositories.MAX_WORKFLOWS
import run.ostrom.server.compiler.domain.repositories.WorkflowsRepository
import run.ostrom.server.compiler.domain.services.CompilerDocs
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.lockKeyUntilCommit
import run.ostrom.server.kernel.database.withOrganization
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.updateReturning
import kotlin.uuid.Uuid

/**
 * [WorkflowsRepository] on PostgreSQL through Exposed: the organization's workflows and the decisions on them, one
 * transaction per call with the organization set (`withOrganization`) for the row level security of the compiler
 * tables, each statement filtered on `org_id` too.
 *
 * @param database The serving role's pool.
 */
class PostgresWorkflowsRepository(private val database: JdbcDatabase) : WorkflowsRepository {
    // `pg_advisory_xact_lock` on a key of the organization: held until the transaction ends, by every replica.
    override suspend fun <T> underWorkflowLock(orgId: Uuid, block: (LockedWorkflows) -> T): T =
        database.withOrganization(orgId) {
            lockKeyUntilCommit("$LOCK_KEY:$orgId")
            block(WorkflowStatements)
        }

    override suspend fun workflows(orgId: Uuid): List<StoredWorkflow> =
        database.withOrganization(orgId) { WorkflowStatements.workflows(orgId) }

    override suspend fun workflow(orgId: Uuid, id: String): StoredWorkflow? =
        database.withOrganization(orgId) { WorkflowStatements.workflow(orgId, id) }

    override suspend fun saveWorkflow(
        orgId: Uuid,
        id: String,
        raw: JsonObject,
        expectedVersion: Int?,
        author: Authorship,
    ): StoredWorkflow? = database.withOrganization(orgId) {
        WorkflowStatements.saveWorkflow(orgId, id, raw, expectedVersion, author)
    }

    override suspend fun deleteWorkflow(orgId: Uuid, id: String): Boolean =
        database.withOrganization(orgId) { WorkflowStatements.deleteWorkflow(orgId, id) }

    override suspend fun workflowCount(orgId: Uuid): Int =
        database.withOrganization(orgId) { WorkflowStatements.workflowCount(orgId) }

    // One row per existing workflow, through compiler_decisions_by_subject: bounded by MAX_WORKFLOWS.
    override suspend fun lastDecisions(orgId: Uuid): List<DecisionDoc> = database.withOrganization(orgId) {
        val d = CompilerDecisions
        val w = CompilerWorkflows
        d.join(w, JoinType.INNER, additionalConstraint = { (w.orgId eq d.orgId) and (w.id eq d.subjectId) })
            .select(d.id, d.subjectKind, d.subjectId, d.decidedBy, d.decidedAt, d.receiptId)
            .withDistinctOn(d.subjectId)
            .where { (d.orgId eq orgId) and (d.subjectKind eq WORKFLOW) }
            .orderBy(d.subjectId to SortOrder.ASC, d.decidedAt to SortOrder.DESC, d.id to SortOrder.DESC)
            .map {
                DecisionDoc(
                    id = it[d.id],
                    subjectKind = it[d.subjectKind],
                    subjectId = it[d.subjectId],
                    by = it[d.decidedBy],
                    at = it[d.decidedAt],
                    receiptId = it[d.receiptId],
                )
            }
    }

    override suspend fun addDecision(orgId: Uuid, decision: DecisionDoc) {
        database.withOrganization(orgId) {
            CompilerDecisions.insert {
                it[this.orgId] = orgId
                it[id] = decision.id
                it[subjectKind] = decision.subjectKind
                it[subjectId] = decision.subjectId
                it[decidedBy] = decision.by
                it[decidedAt] = decision.at
                it[receiptId] = decision.receiptId
            }
        }
    }
}

// The statements on the workflows, run in the caller's transaction: one call's own, or the workflow lock's.
private object WorkflowStatements : LockedWorkflows {
    private val w = CompilerWorkflows
    private val columns = listOf(w.doc, w.version, w.updatedBy, w.updatedAt)

    override fun workflows(orgId: Uuid): List<StoredWorkflow> = w.select(columns)
        .where { w.orgId eq orgId }
        .orderBy(w.createdAt to SortOrder.ASC, w.id to SortOrder.ASC)
        .limit(READ_GUARD)
        .map { it.toWorkflow() }

    override fun workflow(orgId: Uuid, id: String): StoredWorkflow? = w.select(columns)
        .where { (w.orgId eq orgId) and (w.id eq id) }
        .firstOrNull()
        ?.toWorkflow()

    override fun saveWorkflow(
        orgId: Uuid,
        id: String,
        raw: JsonObject,
        expectedVersion: Int?,
        author: Authorship,
    ): StoredWorkflow? {
        val saved = if (expectedVersion == null) {
            // A creation never replaces (ON CONFLICT DO NOTHING).
            w.insertReturning(columns, ignoreErrors = true) {
                it[this.orgId] = orgId
                it[this.id] = id
                it[doc] = raw.toString()
                it[version] = 1
                it[updatedBy] = author.by
                it[updatedAt] = author.at
                it[createdAt] = author.at
            }
        } else {
            // An update replaces only the version it read.
            w.updateReturning(columns, where = {
                (w.orgId eq orgId) and (w.id eq id) and (w.version eq expectedVersion)
            }) {
                it[doc] = raw.toString()
                it[version] = version + 1
                it[updatedBy] = author.by
                it[updatedAt] = author.at
            }
        }
        return saved.firstOrNull()?.toWorkflow()
    }

    override fun deleteWorkflow(orgId: Uuid, id: String): Boolean =
        w.deleteWhere { (w.orgId eq orgId) and (w.id eq id) } > 0

    override fun workflowCount(orgId: Uuid): Int {
        val n = w.id.count()
        return w.select(n).where { w.orgId eq orgId }.single()[n].toInt()
    }

    private fun ResultRow.toWorkflow() = StoredWorkflow(
        doc = CompilerDocs.workflow(json.parseToJsonElement(this[w.doc]).jsonObject),
        version = this[w.version],
        updatedBy = this[w.updatedBy],
        updatedAt = this[w.updatedAt],
    )
}

private const val LOCK_KEY = "ostrom.compiler.workflows"
private const val WORKFLOW = "workflow"

// Two creations at the limit at once may both pass the count: the read keeps room for them, and stays bounded.
private const val READ_GUARD = 2 * MAX_WORKFLOWS

// Top-level, not in a companion: a companion property has a getter nothing calls, which Kover counts.
private val json = Json { ignoreUnknownKeys = true }
