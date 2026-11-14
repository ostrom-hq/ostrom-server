// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.secrets.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Where an environment variant keeps its value.
 *
 * @property wire The stable name in the API and the database.
 */
enum class EnvironmentBinding(val wire: String) {
    /** One encrypted value on the variant: a team-wide value, or a member's personal one. */
    SHARED("shared"),

    /** No value on the variant: each assignment carries the member's own encrypted value. */
    PER_MEMBER("perMember"),
    ;

    /** Constants and readers of the enclosing type. */
    companion object {
        /**
         * Reads a binding.
         *
         * @param wire Its stable name.
         *
         * @return The binding, or null when the name is unknown.
         */
        fun fromWire(wire: String): EnvironmentBinding? = entries.firstOrNull { it.wire == wire }
    }
}

// TeamCode's ranks: a group source would sit between them.
private const val DIRECT_PRECEDENCE = 300
private const val BULK_PRECEDENCE = 100

/**
 * How an assignment was made. A higher [precedence] wins when several sources select the same variable for a member;
 * a group source (between the two, as TeamCode reserves it) would slot in without changing them.
 *
 * @property wire The stable name in the API and the database.
 * @property precedence Its rank.
 */
enum class AssignmentSource(val wire: String, val precedence: Int) {
    /** Selected for this one member, by an administrator or by the member's own personal variant. */
    DIRECT("direct", DIRECT_PRECEDENCE),

    /** Written by a bulk assignment to every member enabled at that moment. */
    BULK("bulk", BULK_PRECEDENCE),
    ;

    /** Constants and readers of the enclosing type. */
    companion object {
        /**
         * Reads a source.
         *
         * @param wire Its stable name.
         *
         * @return The source; an unknown name reads as [BULK], the weakest.
         */
        fun fromWire(wire: String): AssignmentSource = entries.firstOrNull { it.wire == wire } ?: BULK
    }
}

/**
 * An environment variant: a named value for one environment variable (its slot), never with the value. Several
 * variants can fill the same variable (`org-a` and `org-b` for `GITHUB_TOKEN`); a member has at most one per variable.
 *
 * @property id The variant.
 * @property orgId The organization.
 * @property variable The environment variable it fills.
 * @property name Its name, unique per variable and owner, case-insensitively.
 * @property binding Where its value lives.
 * @property ownerMemberId The member a personal variant belongs to, assigned to them alone; null for a team variant.
 * @property hasValue Whether a shared value is stored.
 * @property version Bumped by each change.
 * @property createdAt When it was created.
 * @property updatedAt When it last changed.
 */
data class EnvironmentVariant(
    val id: Uuid,
    val orgId: Uuid,
    val variable: String,
    val name: String,
    val binding: EnvironmentBinding,
    val ownerMemberId: Uuid?,
    val hasValue: Boolean,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** Whether it is a member's personal variant. */
    val personal: Boolean get() = ownerMemberId != null
}

/**
 * A variant with its sealed shared value; the envelope never leaves the domain.
 *
 * @property variant The metadata.
 * @property envelope The sealed value, null for a per-member variant.
 */
data class StoredEnvironmentVariant(val variant: EnvironmentVariant, val envelope: String?) {
    /** The envelope stays out of logs. */
    override fun toString(): String = "StoredEnvironmentVariant(variant=$variant)"
}

/**
 * One member's selection for one variable, metadata only.
 *
 * @property memberId The member.
 * @property variable The variable.
 * @property variantId The variant selected.
 * @property variantName Its name.
 * @property version Its version.
 * @property source How it was selected.
 * @property personal Whether it is the member's own personal variant.
 * @property hasMemberValue Whether a per-member variant's value for this member is stored.
 * @property assignedAt When the selection, or the member's value, last changed.
 */
data class EnvironmentAssignment(
    val memberId: Uuid,
    val variable: String,
    val variantId: Uuid,
    val variantName: String,
    val version: Int,
    val source: AssignmentSource,
    val personal: Boolean,
    val hasMemberValue: Boolean,
    val assignedAt: Instant,
)

/**
 * A member excluded from a variable.
 *
 * @property memberId The member.
 * @property variable The variable.
 */
data class EnvironmentExclusion(val memberId: Uuid, val variable: String)

/**
 * What a bulk assignment did.
 *
 * @property assigned Members now holding the variant.
 * @property keptDirect Members kept on their direct selection.
 * @property excluded Members skipped because they are excluded from the variable.
 * @property changed The members whose selection changed, for the journal.
 */
data class BulkAssignment(val assigned: Int, val keptDirect: Int, val excluded: Int, val changed: List<Uuid>)

/**
 * One assigned variable as the resolution reads it: the selected variant and the sealed value that applies.
 *
 * @property variable The variable.
 * @property variantId The variant.
 * @property binding Its binding, which says which envelope holds the value and with which context; null for one this
 *   version does not know, whose value is withheld.
 * @property envelope The variant's shared value, or the member's own for a per-member variant; null when missing.
 */
data class AssignedEnvironmentValue(
    val variable: String,
    val variantId: Uuid,
    val binding: EnvironmentBinding?,
    val envelope: String?,
) {
    /** The envelope stays out of logs. */
    override fun toString(): String = "AssignedEnvironmentValue(variable=$variable, variantId=$variantId)"
}

/**
 * A member's environment variables with their values: never logged, stored, or shown to anyone else.
 *
 * @property values The values by name.
 * @property unavailable The assigned variables withheld because their value cannot be read now, by name only.
 */
class EnvironmentValues(val values: Map<String, SecretValue>, val unavailable: List<String>) {
    /** Names only. */
    override fun toString(): String = "EnvironmentValues(names=${values.keys.sorted()}, unavailable=$unavailable)"

    /** Constants and readers of the enclosing type. */
    companion object {
        /** No variable. */
        val NONE: EnvironmentValues = EnvironmentValues(emptyMap(), emptyList())
    }
}

/** The context the variant's shared value is sealed with. */
fun environmentContext(orgId: Uuid, variantId: Uuid): String = "environment:$orgId:$variantId"

/** The context a member's own value of a per-member variant is sealed with. */
fun environmentContext(orgId: Uuid, variantId: Uuid, memberId: Uuid): String = "environment:$orgId:$variantId:$memberId"
