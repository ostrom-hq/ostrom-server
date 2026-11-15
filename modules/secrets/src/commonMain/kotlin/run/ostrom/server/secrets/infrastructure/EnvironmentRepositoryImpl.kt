// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.secrets.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.lockKeyUntilCommit
import run.ostrom.server.kernel.database.sumOrZero
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
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.andIfNotNull
import org.jetbrains.exposed.v1.core.case
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.statements.insertValue
import org.jetbrains.exposed.v1.core.stringParam
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteReturning
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.updateReturning
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [EnvironmentRepository] on the tables `environment_*` of [SecretsMigrations]: every statement filters on its
 * organization and runs in it (`inOrg`), under row-level security. Every write that touches a member's selection of a
 * variable (assign, bulk, exclude, a personal variant) takes the transaction-scoped advisory lock of that variable,
 * and every create or rename takes the lock of its owner's names, so concurrent writes on two replicas are serialized
 * by the database. The locks keep the sqlx4k repository's `hashtextextended`, so that both versions take turns during
 * a rollout.
 *
 * @param database The serving role's pool.
 */
@Suppress("TooManyFunctions") // The port's methods, one transaction each.
class EnvironmentRepositoryImpl(private val database: JdbcDatabase) : EnvironmentRepository {
    override suspend fun list(orgId: Uuid, personal: Boolean, ownerMemberId: Uuid?): List<EnvironmentVariant> =
        database.inOrg(orgId) {
            val owner = when {
                !personal -> v.ownerMemberId.isNull()
                ownerMemberId == null -> v.ownerMemberId.isNotNull()
                else -> v.ownerMemberId eq ownerMemberId
            }
            v.select(VARIANT_COLUMNS)
                .where { (v.orgId eq orgId) and owner }
                .orderBy(
                    v.ownerMemberId to SortOrder.ASC_NULLS_FIRST,
                    v.variable to SortOrder.ASC,
                    v.name.lowerCase() to SortOrder.ASC,
                    v.id to SortOrder.ASC,
                )
                .limit(EnvironmentRules.TEAM_LIMIT)
                .map { it.toVariant() }
        }

    override suspend fun get(orgId: Uuid, id: Uuid): StoredEnvironmentVariant? = database.inOrg(orgId) {
        v.select(VARIANT_COLUMNS + v.envelope)
            .where { (v.orgId eq orgId) and (v.id eq id) }
            .singleOrNull()
            ?.let { StoredEnvironmentVariant(it.toVariant(), it[v.envelope]) }
    }

    override suspend fun createTeam(stored: StoredEnvironmentVariant, limit: Int): CreateVariantOutcome {
        val variant = stored.variant
        return database.inOrg(variant.orgId) {
            lockKeyUntilCommit(teamKey(variant.orgId))
            lockKeyUntilCommit(nameKey(variant.orgId, variant.variable, null))
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
        val orgId = variant.orgId
        return database.inOrg(orgId) {
            lockKeyUntilCommit(variableKey(orgId, variant.variable))
            lockKeyUntilCommit(nameKey(orgId, variant.variable, owner))
            // One read of the three counts, as the sqlx4k repository's: the locks are held while it runs.
            val excluded = sumOrZero(
                x.select(x.memberId.count())
                    .where { (x.orgId eq orgId) and (x.memberId eq owner) and (x.variable eq variant.variable) },
            )
            val taken = sumOrZero(
                a.select(a.memberId.count())
                    .where { (a.orgId eq orgId) and (a.memberId eq owner) and (a.variable eq variant.variable) },
            )
            val owned = sumOrZero(v.select(v.id.count()).where { (v.orgId eq orgId) and (v.ownerMemberId eq owner) })
            val counts = Table.Dual.select(excluded, taken, owned).single()
            when {
                counts[excluded] > 0 -> CreateVariantOutcome.EXCLUDED
                counts[taken] > 0 -> CreateVariantOutcome.SLOT_TAKEN
                counts[owned] >= limit -> CreateVariantOutcome.LIMIT
                !insert(stored) -> CreateVariantOutcome.NAME_TAKEN
                else -> {
                    upsertAssignment(orgId, owner, variant, AssignmentSource.DIRECT, null, variant.createdAt)
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
    ): UpdateVariantOutcome = database.inOrg(orgId) {
        val current = v.select(VARIANT_COLUMNS)
            .where { (v.orgId eq orgId) and (v.id eq id) and ownedBy(ownerMemberId) }
            .forUpdate()
            .singleOrNull()
            ?.toVariant()
        when {
            current == null -> UpdateVariantOutcome.NotFound
            current.version != change.expectedVersion -> UpdateVariantOutcome.VersionConflict
            else -> {
                lockKeyUntilCommit(nameKey(orgId, current.variable, ownerMemberId))
                val name = change.name
                if (name != null && nameTaken(orgId, current.variable, ownerMemberId, name, id)) {
                    UpdateVariantOutcome.NameTaken
                } else {
                    val envelope = change.envelope
                    // The row is locked since it was read: the version cannot have moved.
                    val row = v.updateReturning(VARIANT_COLUMNS, where = { (v.orgId eq orgId) and (v.id eq id) }) {
                        if (name != null) it[this.name] = name
                        if (envelope != null) it[this.envelope] = envelope
                        it[version] = version + 1
                        it[updatedAt] = change.updatedAt
                    }.single()
                    UpdateVariantOutcome.Updated(row.toVariant())
                }
            }
        }
    }

    override suspend fun delete(orgId: Uuid, id: Uuid, ownerMemberId: Uuid?): EnvironmentVariant? =
        database.inOrg(orgId) {
            v.deleteReturning(VARIANT_COLUMNS) {
                (v.orgId eq orgId) and (v.id eq id) andIfNotNull ownerMemberId?.let { v.ownerMemberId eq it }
            }.singleOrNull()?.toVariant()
        }

    override suspend fun promote(orgId: Uuid, id: Uuid, updatedAt: Instant, limit: Int): EnvironmentVariant? =
        database.inOrg(orgId) {
            lockKeyUntilCommit(teamKey(orgId))
            val current = lockedVariant(orgId, id)
            if (current == null) {
                null
            } else {
                lockKeyUntilCommit(nameKey(orgId, current.variable, null))
                if (teamCount(orgId) >= limit) throw VariantLimitException(limit)
                if (nameTaken(orgId, current.variable, null, current.name, id)) {
                    null
                } else {
                    v.updateReturning(VARIANT_COLUMNS, where = { (v.orgId eq orgId) and (v.id eq id) }) {
                        it[ownerMemberId] = null
                        it[version] = version + 1
                        it[this.updatedAt] = updatedAt
                    }.single().toVariant()
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
    ): EnvironmentAssignment? = database.inOrg(orgId) {
        lockKeyUntilCommit(variableKey(orgId, variant.variable))
        // Read again under the lock, held until the commit: a variant deleted meanwhile is a 404, not a broken key.
        val fresh = lockedVariant(orgId, variant.id) ?: throw EnvironmentVariantNotFoundException()
        if (excluded(orgId, memberId, variant.variable)) {
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

    override suspend fun unassign(orgId: Uuid, memberId: Uuid, variable: String): Boolean = database.inOrg(orgId) {
        lockKeyUntilCommit(variableKey(orgId, variable))
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
        return database.inOrg(orgId) {
            lockKeyUntilCommit(variableKey(orgId, variant.variable))
            lockedVariant(orgId, variant.id) ?: throw EnvironmentVariantNotFoundException()
            val excluded = x.select(x.memberId)
                .where { (x.orgId eq orgId) and (x.variable eq variant.variable) and (x.memberId inList members) }
                .map { it[x.memberId] }
                .toSet()
            val current = a.select(a.memberId, a.variantId, a.assignedBy)
                .where { (a.orgId eq orgId) and (a.variable eq variant.variable) and (a.memberId inList members) }
                .associate { it[a.memberId] to (it[a.variantId] to it[a.assignedBy]) }
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

    override suspend fun exclude(orgId: Uuid, memberId: Uuid, variable: String): Boolean = database.inOrg(orgId) {
        lockKeyUntilCommit(variableKey(orgId, variable))
        deleteAssignment(orgId, memberId, variable)
        x.insertIgnore {
            it[this.orgId] = orgId
            it[this.memberId] = memberId
            it[this.variable] = variable
        }.insertedCount == 1
    }

    override suspend fun include(orgId: Uuid, memberId: Uuid, variable: String): Boolean = database.inOrg(orgId) {
        lockKeyUntilCommit(variableKey(orgId, variable))
        x.deleteWhere { (x.orgId eq orgId) and (x.memberId eq memberId) and (x.variable eq variable) } == 1
    }

    override suspend fun assignments(orgId: Uuid, memberId: Uuid?): List<EnvironmentAssignment> =
        database.inOrg(orgId) {
            val personal = v.ownerMemberId.isNotNull()
            val hasMemberValue = a.envelope.isNotNull()
            assignedVariants()
                .select(
                    a.memberId, a.variable, a.variantId, v.name, v.version, a.assignedBy, personal, hasMemberValue,
                    a.assignedAt,
                )
                .where { (a.orgId eq orgId) andIfNotNull memberId?.let { a.memberId eq it } }
                .orderBy(a.memberId to SortOrder.ASC, a.variable to SortOrder.ASC)
                .limit(EnvironmentRules.ASSIGNMENTS_PAGE)
                .map { row ->
                    EnvironmentAssignment(
                        memberId = row[a.memberId],
                        variable = row[a.variable],
                        variantId = row[a.variantId],
                        variantName = row[v.name],
                        version = row[v.version],
                        source = AssignmentSource.fromWire(row[a.assignedBy]),
                        personal = row[personal],
                        hasMemberValue = row[hasMemberValue],
                        assignedAt = row[a.assignedAt],
                    )
                }
        }

    override suspend fun exclusions(orgId: Uuid, memberId: Uuid?): List<EnvironmentExclusion> =
        database.inOrg(orgId) {
            x.select(x.memberId, x.variable)
                .where { (x.orgId eq orgId) andIfNotNull memberId?.let { x.memberId eq it } }
                .orderBy(x.memberId to SortOrder.ASC, x.variable to SortOrder.ASC)
                .limit(EnvironmentRules.ASSIGNMENTS_PAGE)
                .map { EnvironmentExclusion(it[x.memberId], it[x.variable]) }
        }

    override suspend fun assignedValues(orgId: Uuid, memberId: Uuid): List<AssignedEnvironmentValue> =
        database.inOrg(orgId) {
            // A shared variant's value is the variant's, a per-member one's the member's own.
            val envelope = case().When(v.binding eq EnvironmentBinding.SHARED.wire, v.envelope).Else(a.envelope)
            assignedVariants()
                .select(a.variable, a.variantId, v.binding, envelope)
                .where { (a.orgId eq orgId) and (a.memberId eq memberId) }
                .orderBy(a.variable)
                .map { row ->
                    // A binding this version cannot read withholds that variable only (null: named in `unavailable`).
                    AssignedEnvironmentValue(
                        variable = row[a.variable],
                        variantId = row[a.variantId],
                        binding = EnvironmentBinding.fromWire(row[v.binding]),
                        envelope = row[envelope],
                    )
                }
        }

    // The assignments with their variant, joined on the organization too.
    private fun assignedVariants() =
        a.join(v, JoinType.INNER, additionalConstraint = { (v.orgId eq a.orgId) and (v.id eq a.variantId) })

    // The variant, its row locked against a delete or a change until the commit.
    private fun lockedVariant(orgId: Uuid, id: Uuid): EnvironmentVariant? = v.select(VARIANT_COLUMNS)
        .where { (v.orgId eq orgId) and (v.id eq id) }
        .forUpdate(ForUpdateOption.PostgreSQL.ForShare())
        .singleOrNull()
        ?.toVariant()

    private fun teamCount(orgId: Uuid): Long =
        v.select(v.id).where { (v.orgId eq orgId) and v.ownerMemberId.isNull() }.count()

    private fun excluded(orgId: Uuid, memberId: Uuid, variable: String): Boolean = x.select(x.memberId)
        .where { (x.orgId eq orgId) and (x.memberId eq memberId) and (x.variable eq variable) }
        .found()

    // ON CONFLICT DO NOTHING: a name taken (case-insensitively, by the same owner) is the use case's refusal.
    private fun insert(stored: StoredEnvironmentVariant): Boolean {
        val variant = stored.variant
        return v.insertIgnore {
            it[id] = variant.id
            it[orgId] = variant.orgId
            it[this.variable] = variant.variable
            it[name] = variant.name
            it[binding] = variant.binding.wire
            it[ownerMemberId] = variant.ownerMemberId
            it[envelope] = stored.envelope
            it[version] = variant.version
            it[createdAt] = variant.createdAt
            it[updatedAt] = variant.updatedAt
        }.insertedCount == 1
    }

    @Suppress("LongParameterList") // The row's columns.
    private fun upsertAssignment(
        orgId: Uuid,
        memberId: Uuid,
        variant: EnvironmentVariant,
        source: AssignmentSource,
        memberEnvelope: String?,
        assignedAt: Instant,
    ) {
        a.upsert(
            a.orgId, a.memberId, a.variable,
            onUpdate = {
                it[a.variantId] = insertValue(a.variantId)
                it[a.assignedBy] = insertValue(a.assignedBy)
                it[a.envelope] = insertValue(a.envelope)
                it[a.assignedAt] = insertValue(a.assignedAt)
            },
        ) {
            it[this.orgId] = orgId
            it[this.memberId] = memberId
            it[variable] = variant.variable
            it[variantId] = variant.id
            it[assignedBy] = source.wire
            it[envelope] = memberEnvelope
            it[this.assignedAt] = assignedAt
        }
    }

    private fun deleteAssignment(orgId: Uuid, memberId: Uuid, variable: String): Boolean =
        a.deleteWhere { (a.orgId eq orgId) and (a.memberId eq memberId) and (a.variable eq variable) } == 1

    private fun nameTaken(orgId: Uuid, variable: String, ownerMemberId: Uuid?, name: String, except: Uuid): Boolean =
        v.select(v.id)
            .where {
                (v.orgId eq orgId) and (v.variable eq variable) and
                    (v.name.lowerCase() eq stringParam(name).lowerCase()) and ownedBy(ownerMemberId) and
                    (v.id neq except)
            }
            .found()

    // Whether the read finds a row.
    private fun Query.found(): Boolean = !empty()

    private fun ownedBy(ownerMemberId: Uuid?): Op<Boolean> =
        if (ownerMemberId == null) v.ownerMemberId.isNull() else v.ownerMemberId eq ownerMemberId

    private fun ResultRow.toVariant(): EnvironmentVariant = EnvironmentVariant(
        id = this[v.id],
        orgId = this[v.orgId],
        variable = this[v.variable],
        name = this[v.name],
        // A binding this version cannot read is served as per-member: it never exposes a shared value.
        binding = EnvironmentBinding.fromWire(this[v.binding]) ?: EnvironmentBinding.PER_MEMBER,
        ownerMemberId = this[v.ownerMemberId],
        hasValue = this[HAS_VALUE],
        version = this[v.version],
        createdAt = this[v.createdAt],
        updatedAt = this[v.updatedAt],
    )
}

private val v = EnvironmentVariants
private val a = EnvironmentAssignments
private val x = EnvironmentExclusions

/** Whether the variant holds a value, read instead of the value itself. */
private val HAS_VALUE = v.envelope.isNotNull()

private val VARIANT_COLUMNS = listOf(
    v.id, v.orgId, v.variable, v.name, v.binding, v.ownerMemberId, HAS_VALUE, v.version, v.createdAt,
    v.updatedAt,
)

private fun variableKey(orgId: Uuid, variable: String) = "environment-variable:$orgId:$variable"

private fun teamKey(orgId: Uuid) = "environment-team:$orgId"

private fun nameKey(orgId: Uuid, variable: String, owner: Uuid?) =
    "environment-name:$orgId:$variable:${owner ?: "team"}"
