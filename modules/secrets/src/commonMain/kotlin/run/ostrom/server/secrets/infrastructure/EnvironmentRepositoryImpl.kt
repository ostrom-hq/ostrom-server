// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.secrets.infrastructure

import run.ostrom.server.secrets.domain.AssignedEnvironmentValue
import run.ostrom.server.secrets.domain.AssignmentSource
import run.ostrom.server.secrets.domain.BulkAssignment
import run.ostrom.server.secrets.domain.CreateVariantOutcome
import run.ostrom.server.secrets.domain.EnvironmentAssignment
import run.ostrom.server.secrets.domain.EnvironmentBinding
import run.ostrom.server.secrets.domain.EnvironmentExclusion
import run.ostrom.server.secrets.domain.EnvironmentRepository
import run.ostrom.server.secrets.domain.EnvironmentRules
import run.ostrom.server.secrets.domain.EnvironmentVariant
import run.ostrom.server.secrets.domain.EnvironmentVariantNotFoundException
import run.ostrom.server.secrets.domain.StoredEnvironmentVariant
import run.ostrom.server.secrets.domain.UpdateVariantOutcome
import run.ostrom.server.secrets.domain.VariantChange
import run.ostrom.server.secrets.domain.VariantLimitException
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [EnvironmentRepository] on PostgreSQL (tables `environment_*` of [SecretsMigrations]). Every write that touches a
 * member's selection of a variable (assign, bulk, exclude, a personal variant) takes the transaction-scoped advisory
 * lock of that variable, and every create or rename takes the lock of its owner's names, so concurrent writes on two
 * replicas are serialized by the database.
 *
 * @param driver The database.
 */
@Suppress("TooManyFunctions") // The port's methods, one statement or one transaction each.
class EnvironmentRepositoryImpl(private val driver: Driver) : EnvironmentRepository {
    override suspend fun list(orgId: Uuid, personal: Boolean, ownerMemberId: Uuid?): List<EnvironmentVariant> {
        val owner = when {
            !personal -> "owner_member_id IS NULL"
            ownerMemberId == null -> "owner_member_id IS NOT NULL"
            else -> "owner_member_id = :owner"
        }
        val statement = Statement.create(
            "SELECT $COLUMNS FROM environment_variants WHERE org_id = :org_id AND $owner " +
                "ORDER BY owner_member_id NULLS FIRST, variable, lower(name), id LIMIT :limit",
        )
            .bind("org_id", orgId)
            .bind("limit", EnvironmentRules.TEAM_LIMIT)
        if (personal && ownerMemberId != null) statement.bind("owner", ownerMemberId)
        return driver.fetchInOrg(orgId, statement).getOrThrow().map { it.toVariant() }
    }

    override suspend fun get(orgId: Uuid, id: Uuid): StoredEnvironmentVariant? = driver.fetchInOrg(
        orgId,
        Statement.create("SELECT $COLUMNS, envelope FROM environment_variants WHERE org_id = :org_id AND id = :id")
            .bind("org_id", orgId)
            .bind("id", id),
    ).getOrThrow().firstOrNull()?.let { StoredEnvironmentVariant(it.toVariant(), it.get("envelope").asStringOrNull()) }

    override suspend fun createTeam(stored: StoredEnvironmentVariant, limit: Int): CreateVariantOutcome {
        val variant = stored.variant
        return driver.inTenant(variant.orgId) {
            lock(teamKey(variant.orgId))
            lock(nameKey(variant.orgId, variant.variable, null))
            when {
                teamCount(variant.orgId) >= limit -> CreateVariantOutcome.LIMIT
                insert(stored) -> CreateVariantOutcome.CREATED
                else -> CreateVariantOutcome.NAME_TAKEN
            }
        }
    }

    override suspend fun createPersonal(stored: StoredEnvironmentVariant, limit: Int): CreateVariantOutcome {
        val variant = stored.variant
        val owner = requireNotNull(variant.ownerMemberId) { "A personal variant has an owner." }
        return driver.inTenant(variant.orgId) {
            lock(variableKey(variant.orgId, variant.variable))
            lock(nameKey(variant.orgId, variant.variable, owner))
            val row = fetchAll(
                Statement.create(
                    "SELECT " +
                        "(SELECT COUNT(*) FROM environment_exclusions WHERE org_id = :org_id AND member_id = :owner " +
                        "AND variable = :variable) AS excluded, " +
                        "(SELECT COUNT(*) FROM environment_assignments WHERE org_id = :org_id AND member_id = :owner " +
                        "AND variable = :variable) AS taken, " +
                        "(SELECT COUNT(*) FROM environment_variants WHERE org_id = :org_id " +
                        "AND owner_member_id = :owner) AS owned",
                )
                    .bind("org_id", variant.orgId)
                    .bind("owner", owner)
                    .bind("variable", variant.variable),
            ).getOrThrow().first()
            when {
                row.get("excluded").asLong() > 0 -> CreateVariantOutcome.EXCLUDED
                row.get("taken").asLong() > 0 -> CreateVariantOutcome.SLOT_TAKEN
                row.get("owned").asLong() >= limit -> CreateVariantOutcome.LIMIT
                !insert(stored) -> CreateVariantOutcome.NAME_TAKEN
                else -> {
                    upsertAssignment(variant.orgId, owner, variant, AssignmentSource.DIRECT, null, variant.createdAt)
                    CreateVariantOutcome.CREATED
                }
            }
        }
    }

    override suspend fun update(
        orgId: Uuid,
        id: Uuid,
        ownerMemberId: Uuid?,
        change: VariantChange,
    ): UpdateVariantOutcome = driver.inTenant(orgId) {
        val current = fetchAll(
            Statement.create(
                "SELECT $COLUMNS FROM environment_variants " +
                    "WHERE org_id = :org_id AND id = :id AND ${owner(ownerMemberId)}",
            ).bindKey(orgId, id, ownerMemberId),
        ).getOrThrow().firstOrNull()?.toVariant()
        when {
            current == null -> UpdateVariantOutcome.NotFound
            current.version != change.expectedVersion -> UpdateVariantOutcome.VersionConflict
            else -> {
                lock(nameKey(orgId, current.variable, ownerMemberId))
                val name = change.name
                if (name != null && nameTaken(orgId, current.variable, ownerMemberId, name, id)) {
                    UpdateVariantOutcome.NameTaken
                } else {
                    val statement = Statement.create(
                        "UPDATE environment_variants SET name = COALESCE(:name, name), " +
                            "envelope = COALESCE(:envelope, envelope), version = version + 1, updated_at = :at " +
                            "WHERE org_id = :org_id AND id = :id AND ${owner(ownerMemberId)} AND version = :expected " +
                            "RETURNING $COLUMNS",
                    ).bindKey(orgId, id, ownerMemberId)
                        .bind("expected", change.expectedVersion)
                        .bind("at", change.updatedAt)
                    if (name == null) statement.bindNull("name", String::class) else statement.bind("name", name)
                    val envelope = change.envelope
                    if (envelope == null) {
                        statement.bindNull("envelope", String::class)
                    } else {
                        statement.bind("envelope", envelope)
                    }
                    fetchAll(statement).getOrThrow().firstOrNull()?.toVariant()
                        ?.let { UpdateVariantOutcome.Updated(it) }
                        ?: UpdateVariantOutcome.VersionConflict
                }
            }
        }
    }

    override suspend fun delete(orgId: Uuid, id: Uuid, ownerMemberId: Uuid?): EnvironmentVariant? {
        val owner = if (ownerMemberId == null) "" else " AND owner_member_id = :owner"
        val statement = Statement.create(
            "DELETE FROM environment_variants WHERE org_id = :org_id AND id = :id$owner RETURNING $COLUMNS",
        )
            .bind("org_id", orgId)
            .bind("id", id)
        if (ownerMemberId != null) statement.bind("owner", ownerMemberId)
        return driver.fetchInOrg(orgId, statement).getOrThrow().firstOrNull()?.toVariant()
    }

    override suspend fun promote(orgId: Uuid, id: Uuid, updatedAt: Instant, limit: Int): EnvironmentVariant? =
        driver.inTenant(orgId) {
            lock(teamKey(orgId))
            val current = lockedVariant(orgId, id)
            if (current == null) {
                null
            } else {
                lock(nameKey(orgId, current.variable, null))
                if (teamCount(orgId) >= limit) throw VariantLimitException(limit)
                if (nameTaken(orgId, current.variable, null, current.name, id)) {
                    null
                } else {
                    fetchAll(
                        Statement.create(
                            "UPDATE environment_variants SET owner_member_id = NULL, version = version + 1, " +
                                "updated_at = :at WHERE org_id = :org_id AND id = :id RETURNING $COLUMNS",
                        )
                            .bind("org_id", orgId)
                            .bind("id", id)
                            .bind("at", updatedAt),
                    ).getOrThrow().firstOrNull()?.toVariant()
                }
            }
        }

    override suspend fun assign(
        orgId: Uuid,
        memberId: Uuid,
        variant: EnvironmentVariant,
        source: AssignmentSource,
        memberEnvelope: String?,
        assignedAt: Instant,
    ): EnvironmentAssignment? = driver.inTenant(orgId) {
        lock(variableKey(orgId, variant.variable))
        // Read again under the lock, held until the commit: a variant deleted meanwhile is a 404, not a broken key.
        val fresh = lockedVariant(orgId, variant.id) ?: throw EnvironmentVariantNotFoundException()
        val excluded = fetchAll(
            Statement.create(
                "SELECT 1 AS excluded FROM environment_exclusions " +
                    "WHERE org_id = :org_id AND member_id = :member AND variable = :variable",
            )
                .bind("org_id", orgId)
                .bind("member", memberId)
                .bind("variable", variant.variable),
        ).getOrThrow().firstOrNull() != null
        if (excluded) {
            null
        } else {
            upsertAssignment(orgId, memberId, fresh, source, memberEnvelope, assignedAt)
            EnvironmentAssignment(
                memberId = memberId,
                variable = fresh.variable,
                variantId = fresh.id,
                variantName = fresh.name,
                version = fresh.version,
                source = source,
                personal = fresh.personal,
                hasMemberValue = memberEnvelope != null,
                assignedAt = assignedAt,
            )
        }
    }

    override suspend fun unassign(orgId: Uuid, memberId: Uuid, variable: String): Boolean = driver.inTenant(orgId) {
        lock(variableKey(orgId, variable))
        deleteAssignment(orgId, memberId, variable)
    }

    override suspend fun bulkAssign(
        orgId: Uuid,
        variant: EnvironmentVariant,
        memberIds: List<Uuid>,
        assignedAt: Instant,
    ): BulkAssignment {
        val members = memberIds.distinct()
        if (members.isEmpty()) return BulkAssignment(0, 0, 0, emptyList())
        return driver.inTenant(orgId) {
            lock(variableKey(orgId, variant.variable))
            lockedVariant(orgId, variant.id) ?: throw EnvironmentVariantNotFoundException()
            val excluded = memberSet(
                "SELECT member_id FROM environment_exclusions " +
                    "WHERE org_id = :org_id AND variable = :variable AND member_id IN :members",
                orgId,
                variant.variable,
                members,
            )
            val current = fetchAll(
                Statement.create(
                    "SELECT member_id, variant_id, source FROM environment_assignments " +
                        "WHERE org_id = :org_id AND variable = :variable AND member_id IN :members",
                )
                    .bind("org_id", orgId)
                    .bind("variable", variant.variable)
                    .bind("members", members),
            ).getOrThrow().associate { row ->
                row.get("member_id").asUuid() to (row.get("variant_id").asUuid() to row.get("source").asString())
            }
            val direct = current.filterValues { it.second == AssignmentSource.DIRECT.wire }.keys
            val targets = members.filter { it !in excluded && it !in direct }
            val changed = targets.filter { current[it]?.first != variant.id }
            for (member in changed) {
                upsertAssignment(orgId, member, variant, AssignmentSource.BULK, null, assignedAt)
            }
            BulkAssignment(
                assigned = targets.size,
                keptDirect = members.count { it in direct && it !in excluded },
                excluded = members.count { it in excluded },
                changed = changed,
            )
        }
    }

    override suspend fun exclude(orgId: Uuid, memberId: Uuid, variable: String): Boolean = driver.inTenant(orgId) {
        lock(variableKey(orgId, variable))
        deleteAssignment(orgId, memberId, variable)
        execute(
            Statement.create(
                "INSERT INTO environment_exclusions (org_id, member_id, variable) " +
                    "VALUES (:org_id, :member, :variable) ON CONFLICT (org_id, member_id, variable) DO NOTHING",
            )
                .bind("org_id", orgId)
                .bind("member", memberId)
                .bind("variable", variable),
        ).getOrThrow() == 1L
    }

    override suspend fun include(orgId: Uuid, memberId: Uuid, variable: String): Boolean = driver.inTenant(orgId) {
        lock(variableKey(orgId, variable))
        execute(
            Statement.create(
                "DELETE FROM environment_exclusions " +
                    "WHERE org_id = :org_id AND member_id = :member AND variable = :variable",
            )
                .bind("org_id", orgId)
                .bind("member", memberId)
                .bind("variable", variable),
        ).getOrThrow() == 1L
    }

    override suspend fun assignments(orgId: Uuid, memberId: Uuid?): List<EnvironmentAssignment> {
        val member = if (memberId == null) "" else " AND a.member_id = :member"
        val statement = Statement.create(
            "SELECT a.member_id, a.variable, a.variant_id, v.name, v.version, a.source, " +
                "(v.owner_member_id IS NOT NULL) AS personal, (a.envelope IS NOT NULL) AS has_member_value, " +
                "a.assigned_at FROM environment_assignments a " +
                "JOIN environment_variants v ON v.org_id = a.org_id AND v.id = a.variant_id " +
                "WHERE a.org_id = :org_id$member ORDER BY a.member_id, a.variable LIMIT :limit",
        ).bind("org_id", orgId).bind("limit", EnvironmentRules.ASSIGNMENTS_PAGE)
        if (memberId != null) statement.bind("member", memberId)
        return driver.fetchInOrg(orgId, statement).getOrThrow().map { row ->
            EnvironmentAssignment(
                memberId = row.get("member_id").asUuid(),
                variable = row.get("variable").asString(),
                variantId = row.get("variant_id").asUuid(),
                variantName = row.get("name").asString(),
                version = row.get("version").asInt(),
                source = AssignmentSource.fromWire(row.get("source").asString()),
                personal = row.get("personal").asBoolean(),
                hasMemberValue = row.get("has_member_value").asBoolean(),
                assignedAt = row.get("assigned_at").asInstant(),
            )
        }
    }

    override suspend fun exclusions(orgId: Uuid, memberId: Uuid?): List<EnvironmentExclusion> {
        val member = if (memberId == null) "" else " AND member_id = :member"
        val statement = Statement.create(
            "SELECT member_id, variable FROM environment_exclusions WHERE org_id = :org_id$member " +
                "ORDER BY member_id, variable LIMIT :limit",
        ).bind("org_id", orgId).bind("limit", EnvironmentRules.ASSIGNMENTS_PAGE)
        if (memberId != null) statement.bind("member", memberId)
        return driver.fetchInOrg(orgId, statement).getOrThrow().map { row ->
            EnvironmentExclusion(row.get("member_id").asUuid(), row.get("variable").asString())
        }
    }

    override suspend fun assignedValues(orgId: Uuid, memberId: Uuid): List<AssignedEnvironmentValue> =
        driver.fetchInOrg(
            orgId,
            Statement.create(
                "SELECT a.variable, a.variant_id, v.binding, " +
                    "CASE WHEN v.binding = 'shared' THEN v.envelope ELSE a.envelope END AS envelope " +
                    "FROM environment_assignments a " +
                    "JOIN environment_variants v ON v.org_id = a.org_id AND v.id = a.variant_id " +
                    "WHERE a.org_id = :org_id AND a.member_id = :member ORDER BY a.variable",
            )
                .bind("org_id", orgId)
                .bind("member", memberId),
        ).getOrThrow().map { row ->
            // A binding this version cannot read withholds that variable only (null: named in `unavailable`).
            AssignedEnvironmentValue(
                variable = row.get("variable").asString(),
                variantId = row.get("variant_id").asUuid(),
                binding = EnvironmentBinding.fromWire(row.get("binding").asString()),
                envelope = row.get("envelope").asStringOrNull(),
            )
        }

    // The variant, its row locked against a delete or a change until the commit.
    private suspend fun Transaction.lockedVariant(orgId: Uuid, id: Uuid): EnvironmentVariant? = fetchAll(
        Statement.create("SELECT $COLUMNS FROM environment_variants WHERE org_id = :org_id AND id = :id FOR SHARE")
            .bind("org_id", orgId)
            .bind("id", id),
    ).getOrThrow().firstOrNull()?.toVariant()

    private suspend fun Transaction.teamCount(orgId: Uuid): Long = fetchAll(
        Statement.create(
            "SELECT COUNT(*) AS n FROM environment_variants WHERE org_id = :org_id AND owner_member_id IS NULL",
        ).bind("org_id", orgId),
    ).getOrThrow().first().get("n").asLong()

    private suspend fun Transaction.lock(key: String) {
        fetchAll(
            Statement.create("SELECT 1 AS locked FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) AS l")
                .bind("key", key),
        ).getOrThrow()
    }

    private suspend fun Transaction.insert(stored: StoredEnvironmentVariant): Boolean {
        val variant = stored.variant
        val statement = Statement.create(
            "INSERT INTO environment_variants (id, org_id, variable, name, binding, owner_member_id, envelope, " +
                "version, created_at, updated_at) VALUES (:id, :org_id, :variable, :name, :binding, :owner, " +
                ":envelope, :version, :created_at, :updated_at) ON CONFLICT DO NOTHING",
        )
            .bind("id", variant.id)
            .bind("org_id", variant.orgId)
            .bind("variable", variant.variable)
            .bind("name", variant.name)
            .bind("binding", variant.binding.wire)
            .bind("version", variant.version)
            .bind("created_at", variant.createdAt)
            .bind("updated_at", variant.updatedAt)
        val owner = variant.ownerMemberId
        if (owner == null) statement.bindNull("owner", Uuid::class) else statement.bind("owner", owner)
        val envelope = stored.envelope
        if (envelope == null) statement.bindNull("envelope", String::class) else statement.bind("envelope", envelope)
        return execute(statement).getOrThrow() == 1L
    }

    @Suppress("LongParameterList") // The row's columns.
    private suspend fun Transaction.upsertAssignment(
        orgId: Uuid,
        memberId: Uuid,
        variant: EnvironmentVariant,
        source: AssignmentSource,
        memberEnvelope: String?,
        assignedAt: Instant,
    ) {
        val statement = Statement.create(
            "INSERT INTO environment_assignments (org_id, member_id, variable, variant_id, source, envelope, " +
                "assigned_at) VALUES (:org_id, :member, :variable, :variant, :source, :envelope, :at) " +
                "ON CONFLICT (org_id, member_id, variable) DO UPDATE SET variant_id = EXCLUDED.variant_id, " +
                "source = EXCLUDED.source, envelope = EXCLUDED.envelope, assigned_at = EXCLUDED.assigned_at",
        )
            .bind("org_id", orgId)
            .bind("member", memberId)
            .bind("variable", variant.variable)
            .bind("variant", variant.id)
            .bind("source", source.wire)
            .bind("at", assignedAt)
        if (memberEnvelope == null) {
            statement.bindNull("envelope", String::class)
        } else {
            statement.bind("envelope", memberEnvelope)
        }
        execute(statement).getOrThrow()
    }

    private suspend fun Transaction.deleteAssignment(orgId: Uuid, memberId: Uuid, variable: String): Boolean =
        execute(
            Statement.create(
                "DELETE FROM environment_assignments " +
                    "WHERE org_id = :org_id AND member_id = :member AND variable = :variable",
            )
                .bind("org_id", orgId)
                .bind("member", memberId)
                .bind("variable", variable),
        ).getOrThrow() == 1L

    private suspend fun Transaction.nameTaken(
        orgId: Uuid,
        variable: String,
        ownerMemberId: Uuid?,
        name: String,
        except: Uuid,
    ): Boolean {
        val statement = Statement.create(
            "SELECT 1 AS taken FROM environment_variants WHERE org_id = :org_id AND variable = :variable " +
                "AND lower(name) = lower(:name) AND ${owner(ownerMemberId)} AND id <> :id",
        ).bindKey(orgId, except, ownerMemberId)
            .bind("variable", variable)
            .bind("name", name)
        return fetchAll(statement).getOrThrow().firstOrNull() != null
    }

    private suspend fun Transaction.memberSet(
        sql: String,
        orgId: Uuid,
        variable: String,
        members: List<Uuid>,
    ): Set<Uuid> = fetchAll(
        Statement.create(sql)
            .bind("org_id", orgId)
            .bind("variable", variable)
            .bind("members", members),
    ).getOrThrow().map { it.get("member_id").asUuid() }.toSet()

    private fun owner(ownerMemberId: Uuid?): String =
        if (ownerMemberId == null) "owner_member_id IS NULL" else "owner_member_id = :owner"

    private fun Statement.bindKey(orgId: Uuid, id: Uuid, ownerMemberId: Uuid?): Statement {
        bind("org_id", orgId)
        bind("id", id)
        if (ownerMemberId != null) bind("owner", ownerMemberId)
        return this
    }

    private fun ResultSet.Row.toVariant(): EnvironmentVariant = EnvironmentVariant(
        id = get("id").asUuid(),
        orgId = get("org_id").asUuid(),
        variable = get("variable").asString(),
        name = get("name").asString(),
        // A binding this version cannot read is served as per-member: it never exposes a shared value.
        binding = EnvironmentBinding.fromWire(get("binding").asString()) ?: EnvironmentBinding.PER_MEMBER,
        ownerMemberId = get("owner_member_id").asUuidOrNull(),
        hasValue = get("has_value").asBoolean(),
        version = get("version").asInt(),
        createdAt = get("created_at").asInstant(),
        updatedAt = get("updated_at").asInstant(),
    )

    private companion object {
        const val COLUMNS = "id, org_id, variable, name, binding, owner_member_id, " +
            "(envelope IS NOT NULL) AS has_value, version, created_at, updated_at"

        fun variableKey(orgId: Uuid, variable: String) = "environment-variable:$orgId:$variable"

        fun teamKey(orgId: Uuid) = "environment-team:$orgId"

        fun nameKey(orgId: Uuid, variable: String, owner: Uuid?) =
            "environment-name:$orgId:$variable:${owner ?: "team"}"
    }
}
