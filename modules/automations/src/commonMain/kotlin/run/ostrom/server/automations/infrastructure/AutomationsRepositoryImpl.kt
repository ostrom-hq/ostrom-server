// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.infrastructure

import run.ostrom.server.automations.domain.Automation
import run.ostrom.server.automations.domain.AutomationDefinition
import run.ostrom.server.automations.domain.AutomationRun
import run.ostrom.server.automations.domain.AutomationsRepository
import run.ostrom.server.automations.domain.ClaimedRun
import run.ostrom.server.automations.domain.CronClaim
import run.ostrom.server.automations.domain.FollowedRun
import run.ostrom.server.automations.domain.PayloadProblem
import run.ostrom.server.automations.domain.RunSource
import run.ostrom.server.automations.domain.RunStatus
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.confineToOrganization
import run.ostrom.server.kernel.database.lockUntilCommit
import run.ostrom.server.kernel.database.rowBefore
import run.ostrom.server.kernel.database.switchToTenantRole
import run.ostrom.server.kernel.database.present
import run.ostrom.server.kernel.http.OstromJson
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.booleanParam
import org.jetbrains.exposed.v1.core.case
import org.jetbrains.exposed.v1.core.compoundAnd
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.stringParam
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import org.jetbrains.exposed.v1.jdbc.upsertReturning
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The sessions of the started runs of one owner's automations since an instant, newest first: what the cap of running
 * automation sessions counts (one read a trigger over the cap's reach, on `automations_runs_started`).
 */
internal fun startedSessionsQuery(orgId: Uuid, owner: String, since: Instant, limit: Int): Query {
    val r = AutomationsRuns
    val a = AutomationsAutomations
    return r.join(a, JoinType.INNER, r.automationId, a.id, additionalConstraint = { a.orgId eq r.orgId })
        .select(r.sessionId)
        .where {
            (r.orgId eq orgId) and (r.status eq RunStatus.STARTED.wire) and (r.createdAt greater since) and
                (a.owner eq owner) and r.sessionId.isNotNull()
        }
        .orderBy(r.createdAt to SortOrder.DESC, r.id to SortOrder.DESC)
        .limit(limit)
}

/**
 * The name of an automation whose action starts a workflow: what refuses the workflow's deletion
 * (`workflow_in_use`), one lookup on `automations_automations_workflow`.
 */
internal fun automationStartingQuery(orgId: Uuid, workflowId: String): Query {
    val a = AutomationsAutomations
    return a.select(a.name)
        .where { (a.orgId eq orgId) and (a.workflowId eq workflowId) }
        .orderBy(a.name to SortOrder.ASC)
        .limit(1)
}

/**
 * The started runs of workflow actions of one organization, least recently followed first, locked and skipping those
 * another replica follows at the same time (on `automations_runs_followed`): the first of the two statements that
 * mark them followed now.
 */
internal fun followedPickQuery(orgId: Uuid, limit: Int): Query {
    val r = AutomationsRuns
    return r.select(r.id)
        .where {
            (r.orgId eq orgId) and (r.status eq RunStatus.STARTED.wire) and r.workflowRunId.isNotNull()
        }
        .orderBy(r.followedAt to SortOrder.ASC_NULLS_FIRST, r.id to SortOrder.ASC)
        .limit(limit)
        .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
}

/**
 * [AutomationsRepository] on PostgreSQL (tables of [AutomationsMigrations]). The definition is JSON; the name and
 * trigger kind are columns for the unique name per owner.
 *
 * Every read and write of an automation or a run runs confined to its organization (`inOrg`, S2-01), every query
 * filtered on `org_id` too. Only `automations_routes` is read across organizations, through the `SECURITY DEFINER`
 * functions of [AutomationsMigrations] (version 3): a webhook finds its automation's organization there, and the
 * scheduler claims the due crons of every organization there, then reads and writes each in its organization.
 *
 * @param database The serving role's pool.
 */
@Suppress("TooManyFunctions") // One implementation of the one port of the feature.
internal class AutomationsRepositoryImpl(private val database: JdbcDatabase) : AutomationsRepository {
    override suspend fun createWithinLimit(automation: Automation, maxPerOwner: Int): Boolean =
        database.inOrg(automation.orgId) {
            // Serialize the count and the insert of one owner, so that two concurrent creations never pass the limit.
            lockUntilCommit("automations:${automation.orgId}:${automation.owner}")
            val a = AutomationsAutomations
            val count = a.id.count()
            val owned = a.select(count)
                .where { (a.orgId eq automation.orgId) and (a.owner eq automation.owner) }
                .single()[count]
            if (owned >= maxPerOwner) return@inOrg false
            a.insert {
                it[a.id] = automation.id
                it[a.orgId] = automation.orgId
                it[a.owner] = automation.owner
                it[a.createdAt] = automation.createdAt
                it.setAutomation(automation)
            }
            AutomationsRoutes.insert {
                it[AutomationsRoutes.automationId] = automation.id
                it[AutomationsRoutes.orgId] = automation.orgId
                it[AutomationsRoutes.nextRunAt] = automation.due
            }
            true
        }

    override suspend fun get(orgId: Uuid, id: Uuid): Automation? = database.inOrg(orgId) {
        val a = AutomationsAutomations
        a.select(AUTOMATION_COLUMNS).where { (a.orgId eq orgId) and (a.id eq id) }.singleOrNull()?.toAutomation()
    }

    override suspend fun getById(id: Uuid): Automation? {
        val route = database.query { scalar(routeOrg(id)) } ?: return null
        return get(route, id)
    }

    override suspend fun list(orgId: Uuid, owner: String?, before: Uuid?, limit: Int): List<Automation> =
        database.inOrg(orgId) {
            val a = AutomationsAutomations
            a.select(AUTOMATION_COLUMNS)
                .where {
                    listOfNotNull(
                        a.orgId eq orgId,
                        owner?.let { a.owner eq it },
                        before?.let {
                            rowBefore(
                                listOf(a.createdAt, a.id),
                                a.select(a.createdAt, a.id).where { (a.id eq it) and (a.orgId eq orgId) },
                            )
                        },
                    ).compoundAnd()
                }
                .orderBy(a.createdAt to SortOrder.DESC, a.id to SortOrder.DESC)
                .limit(limit)
                .map { it.toAutomation() }
        }

    override suspend fun update(automation: Automation, expected: Automation): Automation? =
        database.inOrg(automation.orgId) {
            val a = AutomationsAutomations
            val updated = a.updateReturning(
                AUTOMATION_COLUMNS,
                where = {
                    (a.id eq automation.id) and (a.orgId eq automation.orgId) and (a.owner eq automation.owner) and
                        (a.version eq expected.version) and (a.updatedAt eq expected.updatedAt)
                },
            ) { it.setAutomation(automation) }.firstOrNull()?.toAutomation()
            if (updated != null) schedule(updated.orgId, updated.id, updated.due)
            updated
        }

    override suspend fun delete(orgId: Uuid, id: Uuid): Boolean = database.inOrg(orgId) {
        val a = AutomationsAutomations
        a.deleteWhere { (a.id eq id) and (a.orgId eq orgId) } > 0
    }

    override suspend fun insertRun(run: AutomationRun): AutomationRun = database.inOrg(run.orgId) { storeRun(run) }

    override suspend fun updateRun(run: AutomationRun): Boolean = database.inOrg(run.orgId) {
        // Only a queued run moves on: one cancelled meanwhile stays cancelled. The prompt a waiting run kept goes.
        val r = AutomationsRuns
        r.update({ (r.id eq run.id) and (r.orgId eq run.orgId) and (r.status eq RunStatus.QUEUED.wire) }) {
            it[r.status] = run.status.wire
            it[r.sessionId] = run.sessionId
            it[r.workflowRunId] = run.workflowRunId
            it[r.reason] = run.reason
            it[r.updatedAt] = run.updatedAt
            it[r.prompt] = null
        } > 0
    }

    override suspend fun endRun(run: AutomationRun, expected: RunStatus): Boolean = database.inOrg(run.orgId) {
        val r = AutomationsRuns
        r.update({ (r.id eq run.id) and (r.orgId eq run.orgId) and (r.status eq expected.wire) }) {
            it[r.status] = run.status.wire
            it[r.reason] = run.reason
            it[r.updatedAt] = run.updatedAt
            it[r.prompt] = null
        } > 0
    }

    override suspend fun findRun(orgId: Uuid, automationId: Uuid, id: Uuid): AutomationRun? = database.inOrg(orgId) {
        val r = AutomationsRuns
        r.select(RUN_COLUMNS)
            .where { (r.id eq id) and (r.automationId eq automationId) and (r.orgId eq orgId) }
            .singleOrNull()?.toRun()
    }

    override suspend fun park(orgId: Uuid, runId: Uuid, prompt: String) {
        database.inOrg(orgId) {
            val r = AutomationsRuns
            r.update({ (r.id eq runId) and (r.orgId eq orgId) and (r.status eq RunStatus.QUEUED.wire) }) {
                it[r.prompt] = prompt
            }
        }
    }

    override suspend fun parked(limit: Int): List<AutomationRun> {
        val found = mutableListOf<AutomationRun>()
        for (orgId in organizations()) {
            if (found.size >= limit) break
            val runs = database.inOrg(orgId) {
                val r = AutomationsRuns
                r.select(RUN_COLUMNS)
                    .where {
                        (r.orgId eq orgId) and (r.status eq RunStatus.QUEUED.wire) and r.prompt.isNotNull()
                    }
                    .orderBy(r.createdAt to SortOrder.ASC, r.id to SortOrder.ASC)
                    .limit(limit - found.size)
                    .map { it.toRun() }
            }
            found += runs
        }
        return found
    }

    override suspend fun takeParked(orgId: Uuid, runId: Uuid): String? = database.inOrg(orgId) {
        // The prompt leaves the row with the update: of two replicas that read the run, one gets it, the other waits
        // on the lock, then finds the prompt gone.
        val r = AutomationsRuns
        val taken = r.select(r.prompt)
            .where {
                (r.id eq runId) and (r.orgId eq orgId) and (r.status eq RunStatus.QUEUED.wire) and
                    r.prompt.isNotNull()
            }
            .forUpdate()
            .singleOrNull() ?: return@inOrg null
        r.update({ (r.id eq runId) and (r.orgId eq orgId) }) { it[r.prompt] = null }
        taken[r.prompt]
    }

    override suspend fun startedSessions(orgId: Uuid, owner: String, since: Instant, limit: Int): List<String> =
        database.inOrg(orgId) {
            val r = AutomationsRuns
            startedSessionsQuery(orgId, owner, since, limit).map { it.present(r.sessionId, r.status) }
        }

    override suspend fun followed(now: Instant, limit: Int): List<FollowedRun> = organizations().flatMap { orgId ->
        database.inOrg(orgId) {
            val r = AutomationsRuns
            val picked = followedPickQuery(orgId, limit).map { it[r.id] }
            if (picked.isEmpty()) return@inOrg emptyList()
            r.updateReturning(RUN_COLUMNS, where = { (r.orgId eq orgId) and (r.id inList picked) }) {
                it[r.followedAt] = now
            }.map { FollowedRun(it.toRun(), it.present(r.workflowRunId, r.status)) }
        }
    }

    override suspend fun latestWorkflowRun(orgId: Uuid, automationId: Uuid, except: Uuid): String? =
        database.inOrg(orgId) {
            val r = AutomationsRuns
            r.select(r.workflowRunId)
                .where {
                    (r.orgId eq orgId) and (r.automationId eq automationId) and
                        (r.status eq RunStatus.STARTED.wire) and r.workflowRunId.isNotNull() and (r.id neq except)
                }
                .orderBy(r.createdAt to SortOrder.DESC, r.id to SortOrder.DESC)
                .limit(1)
                .firstOrNull()?.get(r.workflowRunId)
        }

    override suspend fun automationStarting(orgId: Uuid, workflowId: String): String? = database.inOrg(orgId) {
        automationStartingQuery(orgId, workflowId).firstOrNull()?.get(AutomationsAutomations.name)
    }

    override suspend fun findRunByDelivery(orgId: Uuid, automationId: Uuid, deliveryKey: String): AutomationRun? =
        database.inOrg(orgId) {
            val r = AutomationsRuns
            r.select(RUN_COLUMNS)
                .where {
                    (r.orgId eq orgId) and (r.automationId eq automationId) and (r.deliveryKey eq deliveryKey)
                }
                .firstOrNull()?.toRun()
        }

    override suspend fun listRuns(orgId: Uuid, automationId: Uuid, before: Uuid?, limit: Int): List<AutomationRun> =
        database.inOrg(orgId) {
            val r = AutomationsRuns
            r.select(RUN_COLUMNS)
                .where {
                    listOfNotNull(
                        (r.automationId eq automationId) and (r.orgId eq orgId),
                        before?.let {
                            rowBefore(
                                listOf(r.createdAt, r.id),
                                r.select(r.createdAt, r.id)
                                    .where { (r.id eq it) and (r.automationId eq automationId) and (r.orgId eq orgId) },
                            )
                        },
                    ).compoundAnd()
                }
                .orderBy(r.createdAt to SortOrder.DESC, r.id to SortOrder.DESC)
                .limit(limit)
                .map { it.toRun() }
        }

    override suspend fun hasRun(orgId: Uuid, automationId: Uuid, id: Uuid): Boolean = database.inOrg(orgId) {
        val r = AutomationsRuns
        !r.select(r.id)
            .where { (r.id eq id) and (r.automationId eq automationId) and (r.orgId eq orgId) }
            .empty()
    }

    override suspend fun countRuns(
        orgId: Uuid,
        automationId: Uuid,
        since: Instant,
        sources: Set<RunSource>,
        statuses: Set<RunStatus>,
    ): Int = database.inOrg(orgId) {
        val r = AutomationsRuns
        val runs = r.id.count()
        r.select(runs)
            .where {
                (r.orgId eq orgId) and (r.automationId eq automationId) and (r.createdAt greater since) and
                    (r.origin inList sources.map { it.wire }) and (r.status inList statuses.map { it.wire })
            }
            .single()[runs].toInt()
    }

    override suspend fun latestSession(orgId: Uuid, automationId: Uuid, except: Uuid): String? =
        database.inOrg(orgId) {
            val r = AutomationsRuns
            r.select(r.sessionId)
                .where {
                    (r.orgId eq orgId) and (r.automationId eq automationId) and
                        (r.status eq RunStatus.STARTED.wire) and r.sessionId.isNotNull() and (r.id neq except)
                }
                .orderBy(r.createdAt to SortOrder.DESC, r.id to SortOrder.DESC)
                .limit(1)
                .firstOrNull()?.get(r.sessionId)
        }

    override suspend fun claimDue(
        now: Instant,
        limit: Int,
        decide: (Automation, Instant) -> CronClaim,
    ): List<ClaimedRun> = database.query {
        // Each due route is locked by one replica (automations_due_routes, FOR UPDATE SKIP LOCKED); another replica
        // skips it, and the new next slot is written before the lock is released, so a slot is never run twice. The
        // routes are claimed by the serving role, which alone may run the function; then the transaction runs as the
        // tenant role and switches its organization for each automation, whose rows are read and written confined to
        // it.
        val d = AutomationsDueRoutes
        val due = d.of(now, limit).select(d.automationId, d.orgId, d.nextRunAt)
            .map { Triple(it[d.automationId], it[d.orgId], it[d.nextRunAt]) }
        switchToTenantRole()
        due.flatMap { (id, orgId, slot) ->
            confineToOrganization(orgId)
            val a = AutomationsAutomations
            // The automation is there: its route is deleted with it.
            a.select(AUTOMATION_COLUMNS).where { (a.id eq id) and (a.orgId eq orgId) }
                .map { it.toAutomation() }.mapNotNull { automation ->
                    val claim = decide(automation, slot)
                    a.update({ (a.id eq id) and (a.orgId eq orgId) }) {
                        it[a.nextRunAt] = claim.nextRunAt
                        it[a.enabled] = a.enabled and booleanParam(!claim.pause)
                    }
                    schedule(orgId, id, claim.nextRunAt)
                    claim.run?.let { ClaimedRun(automation, storeRun(it), slot) }
                }
        }
    }

    override suspend fun deleteRunsBefore(before: Instant) {
        for (orgId in organizations()) {
            database.inOrg(orgId) {
                val r = AutomationsRuns
                r.deleteWhere { (r.orgId eq orgId) and (r.createdAt less before) }
            }
        }
    }

    override suspend fun failQueuedBefore(before: Instant, now: Instant) {
        for (orgId in organizations()) {
            database.inOrg(orgId) {
                val r = AutomationsRuns
                r.update({
                    (r.orgId eq orgId) and (r.status eq RunStatus.QUEUED.wire) and (r.createdAt less before)
                }) {
                    it[r.status] = RunStatus.FAILED.wire
                    it[r.updatedAt] = now
                    it[r.reason] = case()
                        .When(r.prompt.isNull(), stringParam("interrupted"))
                        .Else(stringParam("capacity_timeout"))
                    it[r.prompt] = null
                }
            }
        }
    }

    /** The organizations that have automations, from the one table read across organizations. */
    private suspend fun organizations(): List<Uuid> = database.query {
        val o = AutomationsOrganizations
        o.all().select(o.orgId).map { it[o.orgId] }
    }

    /** Writes the next slot the scheduler looks at. */
    private fun JdbcTransaction.schedule(orgId: Uuid, id: Uuid, nextRunAt: Instant?) {
        val route = AutomationsRoutes
        route.update({ (route.automationId eq id) and (route.orgId eq orgId) }) { it[route.nextRunAt] = nextRunAt }
    }

    /** The slot the scheduler waits for: only an enabled automation has one. */
    private val Automation.due: Instant? get() = nextRunAt.takeIf { enabled }

    private fun JdbcTransaction.storeRun(run: AutomationRun): AutomationRun {
        // A conflict on the delivery key returns the earlier run (the no-op update makes RETURNING give its row).
        val r = AutomationsRuns
        return r.upsertReturning(
            r.automationId,
            r.deliveryKey,
            returning = RUN_COLUMNS,
            onUpdate = { it[r.deliveryKey] = insertValue(r.deliveryKey) },
        ) {
            it[r.id] = run.id
            it[r.automationId] = run.automationId
            it[r.orgId] = run.orgId
            it[r.origin] = run.source.wire
            it[r.status] = run.status.wire
            it[r.version] = run.version
            it[r.deliveryKey] = run.deliveryKey
            it[r.sessionId] = run.sessionId
            it[r.reason] = run.reason
            it[r.problems] = OstromJson.encodeToString(PROBLEMS, run.problems)
            it[r.slotAt] = run.slotAt
            it[r.createdAt] = run.createdAt
            it[r.updatedAt] = run.updatedAt
            it[r.workflowRunId] = run.workflowRunId
        }.first().toRun()
    }

    /** Writes every column an insert or an update of an automation sets (its key, organization and owner aside). */
    private fun UpdateBuilder<*>.setAutomation(automation: Automation) {
        val a = AutomationsAutomations
        this[a.name] = automation.definition.name
        this[a.triggerKind] = automation.definition.trigger.kind.wire
        this[a.definition] = OstromJson.encodeToString(AutomationDefinition.serializer(), automation.definition)
        this[a.enabled] = automation.enabled
        this[a.version] = automation.version
        this[a.secretGeneration] = automation.secretGeneration
        this[a.previousSecretValidUntil] = automation.previousSecretValidUntil
        this[a.nextRunAt] = automation.nextRunAt
        this[a.updatedAt] = automation.updatedAt
        this[a.workflowId] = automation.definition.action.workflowId
    }

    private fun ResultRow.toAutomation(): Automation {
        val a = AutomationsAutomations
        return Automation(
            id = this[a.id],
            orgId = this[a.orgId],
            owner = this[a.owner],
            definition = OstromJson.decodeFromString(AutomationDefinition.serializer(), this[a.definition]),
            enabled = this[a.enabled],
            version = this[a.version],
            secretGeneration = this[a.secretGeneration],
            previousSecretValidUntil = this[a.previousSecretValidUntil],
            nextRunAt = this[a.nextRunAt],
            createdAt = this[a.createdAt],
            updatedAt = this[a.updatedAt],
        )
    }

    private fun ResultRow.toRun(): AutomationRun {
        val r = AutomationsRuns
        return AutomationRun(
            id = this[r.id],
            automationId = this[r.automationId],
            orgId = this[r.orgId],
            source = RunSource.valueOf(this[r.origin].uppercase()),
            status = RunStatus.valueOf(this[r.status].uppercase()),
            version = this[r.version],
            deliveryKey = this[r.deliveryKey],
            sessionId = this[r.sessionId],
            workflowRunId = this[r.workflowRunId],
            reason = this[r.reason],
            problems = OstromJson.decodeFromString(PROBLEMS, this[r.problems]),
            slotAt = this[r.slotAt],
            createdAt = this[r.createdAt],
            updatedAt = this[r.updatedAt],
        )
    }
}

private val PROBLEMS = ListSerializer(PayloadProblem.serializer())

/** What an automation's reads return. */
private val AUTOMATION_COLUMNS = AutomationsAutomations.let { a ->
    listOf(
        a.id, a.orgId, a.owner, a.name, a.triggerKind, a.definition, a.enabled, a.version, a.secretGeneration,
        a.previousSecretValidUntil, a.nextRunAt, a.createdAt, a.updatedAt, a.workflowId,
    )
}

/** What a run's reads return: every column but the prompt a waiting run keeps and its follow mark. */
private val RUN_COLUMNS = AutomationsRuns.let { r ->
    listOf(
        r.id, r.automationId, r.orgId, r.origin, r.status, r.version, r.deliveryKey, r.sessionId, r.reason,
        r.problems, r.slotAt, r.createdAt, r.updatedAt, r.workflowRunId,
    )
}
