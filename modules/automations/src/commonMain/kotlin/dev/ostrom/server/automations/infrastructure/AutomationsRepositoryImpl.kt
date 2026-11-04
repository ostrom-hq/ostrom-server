// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations.infrastructure

import dev.ostrom.server.automations.domain.Automation
import dev.ostrom.server.automations.domain.AutomationDefinition
import dev.ostrom.server.automations.domain.AutomationRun
import dev.ostrom.server.automations.domain.AutomationsRepository
import dev.ostrom.server.automations.domain.ClaimedRun
import dev.ostrom.server.automations.domain.CronClaim
import dev.ostrom.server.automations.domain.PayloadProblem
import dev.ostrom.server.automations.domain.RunSource
import dev.ostrom.server.automations.domain.RunStatus
import dev.ostrom.server.kernel.http.OstromJson
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [AutomationsRepository] on PostgreSQL (tables of [AutomationsMigrations]). The definition is JSON; the name and
 * trigger kind are columns for the unique name per owner.
 *
 * Every read and write of an automation or a run runs confined to its organization ([inTenant], S2-01). Only
 * `automations_routes` is read across organizations, through the `SECURITY DEFINER` functions of
 * [AutomationsMigrations] (version 3): a webhook finds its automation's organization there, and the scheduler claims
 * the due crons of every organization there, then reads and writes each in its organization.
 *
 * @param driver The database.
 */
@Suppress("TooManyFunctions") // One implementation of the one port of the feature.
internal class AutomationsRepositoryImpl(private val driver: Driver) : AutomationsRepository {
    override suspend fun createWithinLimit(automation: Automation, maxPerOwner: Int): Boolean =
        driver.inTenant(automation.orgId) {
            // Serialize the count and the insert of one owner, so that two concurrent creations never pass the limit.
            fetchAll(
                Statement.create("""SELECT pg_advisory_xact_lock(hashtext(:key))""")
                    .bind("key", "automations:${automation.orgId}:${automation.owner}"),
            ).getOrThrow()
            val owned = fetchAll(
                Statement.create(
                    """SELECT COUNT(*) AS n FROM automations_automations WHERE org_id = :org_id AND owner = :owner""",
                ).bind("org_id", automation.orgId).bind("owner", automation.owner),
            ).getOrThrow().first().get("n").asInt()
            if (owned >= maxPerOwner) return@inTenant false
            execute(
                Statement.create(
                    """
                    INSERT INTO automations_automations ($COLUMNS) VALUES (:id, :org_id, :owner, :name,
                    :trigger_kind, :definition, :enabled, :version, :secret_generation,
                    :previous_secret_valid_until, :next_run_at, :created_at, :updated_at)
                    """,
                ).bindAutomation(automation).bind("created_at", automation.createdAt),
            ).getOrThrow()
            execute(
                Statement.create(
                    """
                    INSERT INTO automations_routes (automation_id, org_id, next_run_at) VALUES (:id, :org_id,
                    :next_run_at)
                    """,
                ).bind("id", automation.id).bind("org_id", automation.orgId).bind("next_run_at", automation.due),
            ).getOrThrow()
            true
        }

    override suspend fun get(orgId: Uuid, id: Uuid): Automation? = driver.inTenant(orgId) { select(id) }

    override suspend fun getById(id: Uuid): Automation? {
        val route = driver.fetchAll(
            Statement.create("""SELECT automations_route_org(CAST(:id AS UUID)) AS org_id""").bind("id", id),
        ).getOrThrow().first().get("org_id").asUuidOrNull() ?: return null
        return get(route, id)
    }

    override suspend fun list(orgId: Uuid, owner: String?, before: Uuid?, limit: Int): List<Automation> {
        val cursor = if (before == null) {
            ""
        } else {
            """ AND (created_at, id) <
                (SELECT created_at, id FROM automations_automations WHERE id = :before AND org_id = :org_id)"""
        }
        val statement = Statement.create(
            """
            SELECT $COLUMNS FROM automations_automations WHERE org_id = :org_id AND (CAST(:owner AS TEXT) IS NULL OR
            owner = :owner)$cursor ORDER BY created_at DESC, id DESC LIMIT :limit
            """,
        ).bind("org_id", orgId).bind("owner", owner).bind("limit", limit)
        if (before != null) statement.bind("before", before)
        return driver.inTenant(orgId) { fetchAll(statement).getOrThrow().map { it.toAutomation() } }
    }

    override suspend fun update(automation: Automation, expected: Automation): Automation? =
        driver.inTenant(automation.orgId) {
            val updated = fetchAll(
                Statement.create(
                    """
                    UPDATE automations_automations SET name = :name, trigger_kind = :trigger_kind, definition =
                    :definition, enabled = :enabled, version = :version, secret_generation = :secret_generation,
                    previous_secret_valid_until = :previous_secret_valid_until, next_run_at = :next_run_at,
                    updated_at = :updated_at WHERE id = :id AND org_id = :org_id AND owner = :owner AND version =
                    :expected_version AND updated_at = :expected_updated_at RETURNING $COLUMNS
                    """,
                ).bindAutomation(automation)
                    .bind("expected_version", expected.version)
                    .bind("expected_updated_at", expected.updatedAt),
            ).getOrThrow().firstOrNull()?.toAutomation()
            if (updated != null) schedule(updated.id, updated.due)
            updated
        }

    override suspend fun delete(orgId: Uuid, id: Uuid): Boolean = driver.inTenant(orgId) {
        fetchAll(
            Statement.create(
                """DELETE FROM automations_automations WHERE id = :id AND org_id = :org_id RETURNING id""",
            ).bind("id", id).bind("org_id", orgId),
        ).getOrThrow().any()
    }

    override suspend fun insertRun(run: AutomationRun): AutomationRun = driver.inTenant(run.orgId) { storeRun(run) }

    override suspend fun updateRun(run: AutomationRun) {
        driver.inTenant(run.orgId) {
            execute(
                Statement.create(
                    """
                    UPDATE automations_runs SET status = :status, session_id = :session_id, reason = :reason,
                    updated_at = :updated_at WHERE id = :id
                    """,
                ).bind("status", run.status.wire)
                    .bind("session_id", run.sessionId)
                    .bind("reason", run.reason)
                    .bind("updated_at", run.updatedAt)
                    .bind("id", run.id),
            ).getOrThrow()
        }
    }

    override suspend fun findRunByDelivery(orgId: Uuid, automationId: Uuid, deliveryKey: String): AutomationRun? =
        driver.inTenant(orgId) {
            fetchAll(
                Statement.create(
                    """
                    SELECT $RUN_COLUMNS FROM automations_runs WHERE automation_id = :automation_id AND delivery_key =
                    :key
                    """,
                ).bind("automation_id", automationId).bind("key", deliveryKey),
            ).getOrThrow().firstOrNull()?.toRun()
        }

    override suspend fun listRuns(orgId: Uuid, automationId: Uuid, before: Uuid?, limit: Int): List<AutomationRun> {
        val cursor = if (before == null) {
            ""
        } else {
            """ AND (created_at, id) < (SELECT created_at, id FROM automations_runs WHERE id = :before AND
                automation_id = :automation_id)"""
        }
        val statement = Statement.create(
            """
            SELECT $RUN_COLUMNS FROM automations_runs WHERE automation_id = :automation_id AND org_id =
            :org_id$cursor ORDER BY created_at DESC, id DESC LIMIT :limit
            """,
        ).bind("automation_id", automationId).bind("org_id", orgId).bind("limit", limit)
        if (before != null) statement.bind("before", before)
        return driver.inTenant(orgId) { fetchAll(statement).getOrThrow().map { it.toRun() } }
    }

    override suspend fun hasRun(orgId: Uuid, automationId: Uuid, id: Uuid): Boolean {
        val statement = Statement.create(
            "SELECT 1 AS one FROM automations_runs WHERE id = :id AND automation_id = :automation_id " +
                "AND org_id = :org_id",
        ).bind("id", id).bind("automation_id", automationId).bind("org_id", orgId)
        return driver.inTenant(orgId) { fetchAll(statement).getOrThrow().firstOrNull() != null }
    }

    override suspend fun countRuns(
        orgId: Uuid,
        automationId: Uuid,
        since: Instant,
        sources: Set<RunSource>,
        statuses: Set<RunStatus>,
    ): Int = driver.inTenant(orgId) {
        fetchAll(
            Statement.create(
                """
                SELECT COUNT(*) AS n FROM automations_runs WHERE automation_id = :automation_id AND created_at > :since
                AND source = ANY(string_to_array(:sources, ',')) AND status = ANY(string_to_array(:statuses, ','))
                """,
            ).bind("automation_id", automationId)
                .bind("since", since)
                // Joined, then split in SQL: a bound list is sent as one record, not as a list of values.
                .bind("sources", sources.joinToString(",") { it.wire })
                .bind("statuses", statuses.joinToString(",") { it.wire }),
        ).getOrThrow().first().get("n").asInt()
    }

    override suspend fun latestSession(orgId: Uuid, automationId: Uuid, except: Uuid): String? =
        driver.inTenant(orgId) {
            fetchAll(
                Statement.create(
                    """
                    SELECT session_id FROM automations_runs WHERE automation_id = :automation_id AND status =
                    'started' AND id <> :except ORDER BY created_at DESC, id DESC LIMIT 1
                    """,
                ).bind("automation_id", automationId).bind("except", except),
            ).getOrThrow().firstOrNull()?.let { it.get("session_id").asStringOrNull() }
        }

    override suspend fun claimDue(
        now: Instant,
        limit: Int,
        decide: suspend (Automation, Instant) -> CronClaim,
    ): List<ClaimedRun> = driver.transaction {
        // Each due route is locked by one replica (automations_due_routes, FOR UPDATE SKIP LOCKED); another replica
        // skips it, and the new next slot is written before the lock is released, so a slot is never run twice. The
        // routes are claimed by the serving role, which alone may run the function; then the transaction runs as the
        // tenant role and switches its organization for each automation, whose rows are read and written confined to
        // it.
        val due = fetchAll(
            Statement.create(
                """
                SELECT automation_id, org_id, next_run_at FROM automations_due_routes(CAST(:now AS TIMESTAMPTZ),
                CAST(:limit AS INTEGER))
                """,
            ).bind("now", now).bind("limit", limit),
        ).getOrThrow().map { row ->
            Triple(row.get("automation_id").asUuid(), row.get("org_id").asUuid(), row.get("next_run_at").asInstant())
        }
        execute("SET LOCAL ROLE $TENANT_ROLE").getOrThrow()
        due.flatMap { (id, orgId, slot) ->
            fetchAll(
                Statement.create("SELECT set_config('$TENANT_SETTING', :org, true)").bind("org", orgId.toString()),
            ).getOrThrow()
            // The automation is there: its route is deleted with it.
            fetchAll(Statement.create("""SELECT $COLUMNS FROM automations_automations WHERE id = :id""").bind("id", id))
                .getOrThrow().map { it.toAutomation() }.mapNotNull { automation ->
                    val claim = decide(automation, slot)
                    execute(
                        Statement.create("""UPDATE automations_automations SET next_run_at = :next WHERE id = :id""")
                            .bind("next", claim.nextRunAt).bind("id", id),
                    ).getOrThrow()
                    schedule(id, claim.nextRunAt)
                    claim.run?.let { ClaimedRun(automation, storeRun(it), slot) }
                }
        }
    }

    override suspend fun deleteRunsBefore(before: Instant) {
        for (orgId in organizations()) {
            driver.inTenant(orgId) {
                execute(
                    Statement.create("""DELETE FROM automations_runs WHERE org_id = :org_id AND created_at < :before""")
                        .bind("org_id", orgId).bind("before", before),
                ).getOrThrow()
            }
        }
    }

    override suspend fun failQueuedBefore(before: Instant, now: Instant) {
        for (orgId in organizations()) {
            driver.inTenant(orgId) {
                execute(
                    Statement.create(
                        """
                        UPDATE automations_runs SET status = 'failed', reason = 'interrupted', updated_at = :now
                        WHERE org_id = :org_id AND status = 'queued' AND created_at < :before
                        """,
                    ).bind("now", now).bind("org_id", orgId).bind("before", before),
                ).getOrThrow()
            }
        }
    }

    /** The organizations that have automations, from the one table read across organizations. */
    private suspend fun organizations(): List<Uuid> = driver.fetchAll(
        Statement.create("""SELECT org_id FROM automations_organizations() AS o (org_id)"""),
    ).getOrThrow().map { it.get("org_id").asUuid() }

    /** Writes the next slot the scheduler looks at. */
    private suspend fun QueryExecutor.schedule(id: Uuid, nextRunAt: Instant?) {
        execute(
            Statement.create("""UPDATE automations_routes SET next_run_at = :next WHERE automation_id = :id""")
                .bind("next", nextRunAt).bind("id", id),
        ).getOrThrow()
    }

    private suspend fun QueryExecutor.select(id: Uuid): Automation? = fetchAll(
        Statement.create("""SELECT $COLUMNS FROM automations_automations WHERE id = :id""").bind("id", id),
    ).getOrThrow().firstOrNull()?.toAutomation()

    /** The slot the scheduler waits for: only an enabled automation has one. */
    private val Automation.due: Instant? get() = nextRunAt.takeIf { enabled }

    private suspend fun QueryExecutor.storeRun(run: AutomationRun): AutomationRun = fetchAll(
        // A conflict on the delivery key returns the earlier run (the no-op update makes RETURNING give its row).
        Statement.create(
            """
            INSERT INTO automations_runs ($RUN_COLUMNS) VALUES (:id, :automation_id, :org_id, :source, :status,
            :version, :delivery_key, :session_id, :reason, :problems, :slot_at, :created_at, :updated_at) ON CONFLICT
            (automation_id, delivery_key) DO UPDATE SET delivery_key = EXCLUDED.delivery_key RETURNING $RUN_COLUMNS
            """,
        ).bind("id", run.id)
            .bind("automation_id", run.automationId)
            .bind("org_id", run.orgId)
            .bind("source", run.source.wire)
            .bind("status", run.status.wire)
            .bind("version", run.version)
            .bind("delivery_key", run.deliveryKey)
            .bind("session_id", run.sessionId)
            .bind("reason", run.reason)
            .bind("problems", OstromJson.encodeToString(PROBLEMS, run.problems))
            .bind("slot_at", run.slotAt)
            .bind("created_at", run.createdAt)
            .bind("updated_at", run.updatedAt),
    ).getOrThrow().first().toRun()

    /** Binds every column an insert or update writes (sqlx4k refuses a name the statement does not use). */
    private fun Statement.bindAutomation(automation: Automation): Statement = bind("id", automation.id)
        .bind("org_id", automation.orgId)
        .bind("owner", automation.owner)
        .bind("name", automation.definition.name)
        .bind("trigger_kind", automation.definition.trigger.kind.wire)
        .bind("definition", OstromJson.encodeToString(AutomationDefinition.serializer(), automation.definition))
        .bind("enabled", automation.enabled)
        .bind("version", automation.version)
        .bind("secret_generation", automation.secretGeneration)
        .bind("previous_secret_valid_until", automation.previousSecretValidUntil)
        .bind("next_run_at", automation.nextRunAt)
        .bind("updated_at", automation.updatedAt)

    private fun ResultSet.Row.toAutomation(): Automation = Automation(
        id = get("id").asUuid(),
        orgId = get("org_id").asUuid(),
        owner = get("owner").asString(),
        definition = OstromJson.decodeFromString(AutomationDefinition.serializer(), get("definition").asString()),
        enabled = get("enabled").asBoolean(),
        version = get("version").asInt(),
        secretGeneration = get("secret_generation").asInt(),
        previousSecretValidUntil = get("previous_secret_valid_until").asInstantOrNull(),
        nextRunAt = get("next_run_at").asInstantOrNull(),
        createdAt = get("created_at").asInstant(),
        updatedAt = get("updated_at").asInstant(),
    )

    private fun ResultSet.Row.toRun(): AutomationRun = AutomationRun(
        id = get("id").asUuid(),
        automationId = get("automation_id").asUuid(),
        orgId = get("org_id").asUuid(),
        source = RunSource.valueOf(get("source").asString().uppercase()),
        status = RunStatus.valueOf(get("status").asString().uppercase()),
        version = get("version").asInt(),
        deliveryKey = get("delivery_key").asString(),
        sessionId = get("session_id").asStringOrNull(),
        reason = get("reason").asStringOrNull(),
        problems = OstromJson.decodeFromString(PROBLEMS, get("problems").asString()),
        slotAt = get("slot_at").asInstantOrNull(),
        createdAt = get("created_at").asInstant(),
        updatedAt = get("updated_at").asInstant(),
    )

    private companion object {
        private val PROBLEMS = ListSerializer(PayloadProblem.serializer())

        const val COLUMNS = """
            id, org_id, owner, name, trigger_kind, definition, enabled, version, secret_generation,
            previous_secret_valid_until, next_run_at, created_at, updated_at
        """
        const val RUN_COLUMNS = """
            id, automation_id, org_id, source, status, version, delivery_key, session_id, reason, problems, slot_at,
            created_at, updated_at
        """
    }
}
