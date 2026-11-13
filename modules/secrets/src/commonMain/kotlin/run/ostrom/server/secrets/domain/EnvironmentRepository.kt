// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.secrets.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** What creating a variant did. */
enum class CreateVariantOutcome {
    CREATED,

    /** Another variant of the same owner has this name for the variable. */
    NAME_TAKEN,

    /** The organization (team) or the member (personal) holds its limit. */
    LIMIT,

    /** Personal only: the member already has a selection for the variable. */
    SLOT_TAKEN,

    /** Personal only: the member is excluded from the variable. */
    EXCLUDED,
}

/** What updating a variant did. */
sealed interface UpdateVariantOutcome {
    /** Changed. */
    data class Updated(val variant: EnvironmentVariant) : UpdateVariantOutcome

    /** No such variant of that owner. */
    data object NotFound : UpdateVariantOutcome

    /** Its version is not the one read. */
    data object VersionConflict : UpdateVariantOutcome

    /** The new name is taken. */
    data object NameTaken : UpdateVariantOutcome
}

/**
 * A change to a variant: a new name, a new sealed value, or both.
 *
 * @property expectedVersion The version read.
 * @property name The new name, or null to keep it.
 * @property envelope The new sealed shared value, or null to keep it.
 * @property updatedAt The change time.
 */
data class VariantChange(val expectedVersion: Int, val name: String?, val envelope: String?, val updatedAt: Instant) {
    /** The envelope stays out of logs. */
    override fun toString(): String = "VariantChange(expectedVersion=$expectedVersion, name=$name)"
}

/**
 * Storage of environment variants, their assignments and exclusions; every method is scoped by the organization, and
 * each write is one transaction that serializes on the member and variable it touches, so an exclusion and an
 * assignment never cross.
 */
@Suppress("TooManyFunctions") // One port for the three tables the variants, assignments and exclusions share.
interface EnvironmentRepository {
    /**
     * Lists variants.
     *
     * @param orgId The organization.
     * @param personal False for team variants, true for personal ones.
     * @param ownerMemberId With [personal], only this member's; null for every member's.
     *
     * @return At most [EnvironmentRules.TEAM_LIMIT], by owner, variable, then name.
     */
    suspend fun list(orgId: Uuid, personal: Boolean, ownerMemberId: Uuid?): List<EnvironmentVariant>

    /**
     * Reads a variant.
     *
     * @return The variant and its envelope, or null.
     */
    suspend fun get(orgId: Uuid, id: Uuid): StoredEnvironmentVariant?

    /**
     * Inserts a team variant, under the organization's [limit].
     *
     * @return [CreateVariantOutcome.CREATED], [CreateVariantOutcome.NAME_TAKEN] or [CreateVariantOutcome.LIMIT].
     */
    suspend fun createTeam(stored: StoredEnvironmentVariant, limit: Int): CreateVariantOutcome

    /**
     * Inserts a personal variant and selects it for its owner (`direct`) in one transaction, under the member's
     * [limit], unless the owner already has a selection for the variable or is excluded from it.
     *
     * @return What happened; nothing is written unless [CreateVariantOutcome.CREATED].
     */
    suspend fun createPersonal(stored: StoredEnvironmentVariant, limit: Int): CreateVariantOutcome

    /**
     * Changes a variant if its version is [VariantChange.expectedVersion], bumping it.
     *
     * @param ownerMemberId The owner the variant must have: null for a team variant.
     */
    suspend fun update(orgId: Uuid, id: Uuid, ownerMemberId: Uuid?, change: VariantChange): UpdateVariantOutcome

    /**
     * Deletes a variant and its assignments.
     *
     * @param ownerMemberId When set, only a variant of that owner.
     *
     * @return The deleted variant, or null when there was none.
     */
    suspend fun delete(orgId: Uuid, id: Uuid, ownerMemberId: Uuid?): EnvironmentVariant?

    /**
     * Clears a personal variant's owner, unless a team variant has the same name for the variable.
     *
     * @return The team variant, or null when a team variant has the name (the caller has checked it exists).
     */
    suspend fun promote(orgId: Uuid, id: Uuid, updatedAt: Instant): EnvironmentVariant?

    /**
     * Selects a variant for a member's variable, replacing any selection, unless the member is excluded from it.
     *
     * @param memberEnvelope The member's sealed value for a per-member variant.
     *
     * @return The assignment, or null when the member is excluded.
     */
    @Suppress("LongParameterList") // The row's columns.
    suspend fun assign(
        orgId: Uuid,
        memberId: Uuid,
        variant: EnvironmentVariant,
        source: AssignmentSource,
        memberEnvelope: String?,
        assignedAt: Instant,
    ): EnvironmentAssignment?

    /**
     * Removes a member's selection for a variable.
     *
     * @return True when one was removed.
     */
    suspend fun unassign(orgId: Uuid, memberId: Uuid, variable: String): Boolean

    /**
     * Selects a shared variant for each of [memberIds], as `bulk`: replaces their bulk selections of the variable,
     * keeps direct ones, skips excluded members.
     */
    suspend fun bulkAssign(
        orgId: Uuid,
        variant: EnvironmentVariant,
        memberIds: List<Uuid>,
        assignedAt: Instant,
    ): BulkAssignment

    /**
     * Excludes a member from a variable and removes their selection of it.
     *
     * @return True when the exclusion is new.
     */
    suspend fun exclude(orgId: Uuid, memberId: Uuid, variable: String): Boolean

    /**
     * Removes an exclusion.
     *
     * @return True when one was removed.
     */
    suspend fun include(orgId: Uuid, memberId: Uuid, variable: String): Boolean

    /** The assignments of every member, or of [memberId] only, by member then variable. */
    suspend fun assignments(orgId: Uuid, memberId: Uuid?): List<EnvironmentAssignment>

    /** The exclusions of every member, or of [memberId] only, by member then variable. */
    suspend fun exclusions(orgId: Uuid, memberId: Uuid?): List<EnvironmentExclusion>

    /** A member's assigned variables with the envelope that holds each value, in one query. */
    suspend fun assignedValues(orgId: Uuid, memberId: Uuid): List<AssignedEnvironmentValue>
}
