// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.secrets.domain

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.MemberDirectory
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Who makes a change: the member, and the credential the journal names.
 *
 * @property caller The member.
 * @property principal The credential.
 */
data class EnvironmentActor(val caller: Caller, val principal: AuthenticatedPrincipal)

/**
 * A variant to create.
 *
 * @property variable The environment variable it fills.
 * @property name Its name.
 * @property binding Where its value lives.
 * @property value The shared value, required for [EnvironmentBinding.SHARED] and refused otherwise.
 */
data class NewEnvironmentVariant(
    val variable: String,
    val name: String,
    val binding: EnvironmentBinding,
    val value: SecretValue?,
)

/**
 * A change to a variant.
 *
 * @property expectedVersion The version read.
 * @property name A new name, or null.
 * @property value A new shared value, or null.
 */
data class EnvironmentVariantUpdate(val expectedVersion: Int, val name: String?, val value: SecretValue?)

/** The administrators' management of the organization's environment variants (TeamCode's variants, `ENV` kind). */
interface ManageEnvironmentVariantsUseCase {
    /**
     * The team's variants, or every member's personal ones.
     *
     * @throws AdminRequiredException When the caller does not administer the organization.
     */
    suspend fun list(caller: Caller, personal: Boolean): List<EnvironmentVariant>

    /**
     * Creates a team variant.
     *
     * @throws InvalidEnvironmentVariantException When it is malformed.
     * @throws VariantExistsException When the name is taken for the variable.
     * @throws VariantLimitException At [EnvironmentRules.TEAM_LIMIT].
     */
    suspend fun create(actor: EnvironmentActor, variant: NewEnvironmentVariant): EnvironmentVariant

    /**
     * Renames a team variant or replaces its shared value.
     *
     * @throws VariantPersonalException For a personal variant.
     * @throws VariantVersionConflictException When it changed since it was read.
     */
    suspend fun update(actor: EnvironmentActor, id: Uuid, update: EnvironmentVariantUpdate): EnvironmentVariant

    /** Deletes a variant, team or personal, and its assignments. */
    suspend fun delete(actor: EnvironmentActor, id: Uuid)

    /**
     * Assigns a shared team variant to every member enabled now.
     *
     * @throws VariantNotSharedException For a per-member variant.
     */
    suspend fun bulkAssign(actor: EnvironmentActor, id: Uuid): BulkAssignment

    /**
     * Makes a personal variant a team variant.
     *
     * @throws VariantExistsException When a team variant has its name for the variable.
     */
    suspend fun promote(actor: EnvironmentActor, id: Uuid): EnvironmentVariant
}

/**
 * [ManageEnvironmentVariantsUseCase] on the repository.
 *
 * @param repository The storage.
 * @param cipher The secrets key.
 * @param members The organization's members.
 * @param audit The journal.
 * @param clock The time.
 */
class ManageEnvironmentVariantsUseCaseImpl(
    private val repository: EnvironmentRepository,
    private val cipher: SecretCipher,
    private val members: MemberDirectory,
    private val audit: OrgAuditTrail,
    private val clock: Clock,
) : ManageEnvironmentVariantsUseCase {
    override suspend fun list(caller: Caller, personal: Boolean): List<EnvironmentVariant> {
        caller.requireAdmin()
        return repository.list(caller.orgId, personal, null)
    }

    override suspend fun create(actor: EnvironmentActor, variant: NewEnvironmentVariant): EnvironmentVariant {
        actor.caller.requireAdmin()
        val stored = sealNew(cipher, actor.caller.orgId, null, variant, clock)
        when (repository.createTeam(stored, EnvironmentRules.TEAM_LIMIT)) {
            CreateVariantOutcome.CREATED -> Unit
            CreateVariantOutcome.LIMIT -> throw VariantLimitException(EnvironmentRules.TEAM_LIMIT)
            else -> throw VariantExistsException()
        }
        journal(audit, actor, "environment.variant.created", stored.variant)
        return stored.variant
    }

    override suspend fun update(
        actor: EnvironmentActor,
        id: Uuid,
        update: EnvironmentVariantUpdate,
    ): EnvironmentVariant {
        actor.caller.requireAdmin()
        val current = (repository.get(actor.caller.orgId, id) ?: throw EnvironmentVariantNotFoundException()).variant
        if (current.personal) throw VariantPersonalException()
        val updated = change(repository, cipher, current, update, clock)
        journal(audit, actor, "environment.variant.updated", updated)
        return updated
    }

    override suspend fun delete(actor: EnvironmentActor, id: Uuid) {
        actor.caller.requireAdmin()
        val deleted = repository.delete(actor.caller.orgId, id, null) ?: throw EnvironmentVariantNotFoundException()
        journal(audit, actor, "environment.variant.deleted", deleted)
    }

    override suspend fun bulkAssign(actor: EnvironmentActor, id: Uuid): BulkAssignment {
        actor.caller.requireAdmin()
        val variant = (repository.get(actor.caller.orgId, id) ?: throw EnvironmentVariantNotFoundException()).variant
        if (variant.personal) throw VariantPersonalException()
        if (variant.binding != EnvironmentBinding.SHARED) throw VariantNotSharedException()
        val result = repository.bulkAssign(
            actor.caller.orgId,
            variant,
            // A directory that cannot list them, or more than the bulk reaches, grants nobody.
            members.memberIds(actor.caller.orgId, EnvironmentRules.BULK_MEMBERS).orEmpty().mapNotNull(Uuid::parseOrNull),
            clock.now(),
        )
        journal(audit, actor, "environment.variant.bulk_assigned", variant, "assigned" to result.assigned)
        for (member in result.changed) {
            audit.record(
                OrgAuditRecord(
                    actor.caller.orgId,
                    actor.principal,
                    "environment.assigned",
                    MEMBER_SUBJECT,
                    member.toString(),
                ),
            )
        }
        return result
    }

    override suspend fun promote(actor: EnvironmentActor, id: Uuid): EnvironmentVariant {
        actor.caller.requireAdmin()
        val current = (repository.get(actor.caller.orgId, id) ?: throw EnvironmentVariantNotFoundException()).variant
        if (!current.personal) return current
        val promoted = repository.promote(actor.caller.orgId, id, clock.now(), EnvironmentRules.TEAM_LIMIT)
            ?: throw VariantExistsException()
        journal(audit, actor, "environment.variant.promoted", promoted)
        return promoted
    }
}

/** The administrators' assignments and exclusions of environment variables. */
interface ManageEnvironmentAssignmentsUseCase {
    /** Every member's assignments and exclusions, or one member's. */
    suspend fun list(caller: Caller, memberId: Uuid?): Pair<List<EnvironmentAssignment>, List<EnvironmentExclusion>>

    /**
     * Selects a team variant for a member's variable.
     *
     * @param value The member's own value, for a per-member variant only.
     *
     * @throws VariantExcludedException While the member is excluded from the variable.
     */
    suspend fun assign(
        actor: EnvironmentActor,
        memberId: Uuid,
        variable: String,
        variantId: Uuid,
        value: SecretValue?,
    ): EnvironmentAssignment

    /** Removes a member's selection for a variable. */
    suspend fun unassign(actor: EnvironmentActor, memberId: Uuid, variable: String)

    /** Excludes a member from a variable, removing their selection. */
    suspend fun exclude(actor: EnvironmentActor, memberId: Uuid, variable: String)

    /** Removes an exclusion; it grants nothing back. */
    suspend fun include(actor: EnvironmentActor, memberId: Uuid, variable: String)
}

/**
 * [ManageEnvironmentAssignmentsUseCase] on the repository.
 *
 * @param repository The storage.
 * @param cipher The secrets key.
 * @param members The organization's members.
 * @param audit The journal.
 * @param clock The time.
 */
class ManageEnvironmentAssignmentsUseCaseImpl(
    private val repository: EnvironmentRepository,
    private val cipher: SecretCipher,
    private val members: MemberDirectory,
    private val audit: OrgAuditTrail,
    private val clock: Clock,
) : ManageEnvironmentAssignmentsUseCase {
    override suspend fun list(
        caller: Caller,
        memberId: Uuid?,
    ): Pair<List<EnvironmentAssignment>, List<EnvironmentExclusion>> {
        caller.requireAdmin()
        return repository.assignments(caller.orgId, memberId) to repository.exclusions(caller.orgId, memberId)
    }

    // The refusals of an assignment: who, what, which value.
    @Suppress("ThrowsCount", "CyclomaticComplexMethod")
    override suspend fun assign(
        actor: EnvironmentActor,
        memberId: Uuid,
        variable: String,
        variantId: Uuid,
        value: SecretValue?,
    ): EnvironmentAssignment {
        actor.caller.requireAdmin()
        val orgId = actor.caller.orgId
        requireMember(members, orgId, memberId)
        val variant = (repository.get(orgId, variantId) ?: throw EnvironmentVariantNotFoundException()).variant
        if (variant.variable != variable) {
            throw InvalidEnvironmentVariantException("This variant fills ${variant.variable}, not $variable.")
        }
        if (variant.personal) throw VariantPersonalException()
        val envelope = when (variant.binding) {
            EnvironmentBinding.SHARED -> {
                if (value != null) {
                    throw InvalidEnvironmentVariantException(
                        "A shared variant keeps its value on the variant: send no member value.",
                    )
                }
                null
            }
            EnvironmentBinding.PER_MEMBER -> {
                val memberValue = value ?: throw InvalidEnvironmentVariantException(
                    "A per-member variant needs the member's value.",
                )
                EnvironmentRules.requireValue(memberValue)
                cipher.seal(
                    memberValue.reveal().encodeToByteArray(),
                    environmentContext(orgId, variant.id, memberId),
                )
            }
        }
        val assignment = repository.assign(orgId, memberId, variant, AssignmentSource.DIRECT, envelope, clock.now())
            ?: throw VariantExcludedException()
        recordMember(audit, actor, "environment.assigned", memberId, variable)
        return assignment
    }

    override suspend fun unassign(actor: EnvironmentActor, memberId: Uuid, variable: String) {
        actor.caller.requireAdmin()
        EnvironmentRules.requireVariable(variable)
        requireMember(members, actor.caller.orgId, memberId)
        if (repository.unassign(actor.caller.orgId, memberId, variable)) {
            recordMember(audit, actor, "environment.unassigned", memberId, variable)
        }
    }

    override suspend fun exclude(actor: EnvironmentActor, memberId: Uuid, variable: String) {
        actor.caller.requireAdmin()
        EnvironmentRules.requireVariable(variable)
        requireMember(members, actor.caller.orgId, memberId)
        if (repository.exclude(actor.caller.orgId, memberId, variable)) {
            recordMember(audit, actor, "environment.excluded", memberId, variable)
        }
    }

    override suspend fun include(actor: EnvironmentActor, memberId: Uuid, variable: String) {
        actor.caller.requireAdmin()
        EnvironmentRules.requireVariable(variable)
        requireMember(members, actor.caller.orgId, memberId)
        if (repository.include(actor.caller.orgId, memberId, variable)) {
            recordMember(audit, actor, "environment.included", memberId, variable)
        }
    }
}

/** A member's own personal environment variants (TeamCode #269): declared by them, assigned to them alone. */
interface PersonalEnvironmentVariantsUseCase {
    /** The caller's personal variants. */
    suspend fun list(caller: Caller): List<EnvironmentVariant>

    /**
     * Declares one and selects it for the caller.
     *
     * @throws SlotTakenException When the caller already has a selection for the variable.
     * @throws VariantExcludedException When the caller is excluded from it.
     * @throws VariantLimitException At [EnvironmentRules.PERSONAL_LIMIT].
     */
    suspend fun create(actor: EnvironmentActor, variant: NewEnvironmentVariant): EnvironmentVariant

    /** Renames one or replaces its value. */
    suspend fun update(actor: EnvironmentActor, id: Uuid, update: EnvironmentVariantUpdate): EnvironmentVariant

    /** Deletes one and its selection. */
    suspend fun delete(actor: EnvironmentActor, id: Uuid)
}

/**
 * [PersonalEnvironmentVariantsUseCase] on the repository.
 *
 * @param repository The storage.
 * @param cipher The secrets key.
 * @param audit The journal.
 * @param clock The time.
 */
class PersonalEnvironmentVariantsUseCaseImpl(
    private val repository: EnvironmentRepository,
    private val cipher: SecretCipher,
    private val audit: OrgAuditTrail,
    private val clock: Clock,
) : PersonalEnvironmentVariantsUseCase {
    override suspend fun list(caller: Caller): List<EnvironmentVariant> =
        repository.list(caller.orgId, true, caller.memberId)

    override suspend fun create(actor: EnvironmentActor, variant: NewEnvironmentVariant): EnvironmentVariant {
        if (variant.binding != EnvironmentBinding.SHARED) {
            throw InvalidEnvironmentVariantException("A personal variant holds your own value: its binding is shared.")
        }
        val stored = sealNew(cipher, actor.caller.orgId, actor.caller.memberId, variant, clock)
        when (repository.createPersonal(stored, EnvironmentRules.PERSONAL_LIMIT)) {
            CreateVariantOutcome.CREATED -> Unit
            CreateVariantOutcome.LIMIT -> throw VariantLimitException(EnvironmentRules.PERSONAL_LIMIT)
            CreateVariantOutcome.SLOT_TAKEN -> throw SlotTakenException()
            CreateVariantOutcome.EXCLUDED -> throw VariantExcludedException()
            CreateVariantOutcome.NAME_TAKEN -> throw VariantExistsException()
        }
        journal(audit, actor, "environment.variant.personal.created", stored.variant)
        return stored.variant
    }

    override suspend fun update(
        actor: EnvironmentActor,
        id: Uuid,
        update: EnvironmentVariantUpdate,
    ): EnvironmentVariant {
        val current = repository.get(actor.caller.orgId, id)?.variant
        if (current == null || current.ownerMemberId != actor.caller.memberId) {
            throw EnvironmentVariantNotFoundException()
        }
        val updated = change(repository, cipher, current, update, clock)
        journal(audit, actor, "environment.variant.personal.updated", updated)
        return updated
    }

    override suspend fun delete(actor: EnvironmentActor, id: Uuid) {
        val deleted = repository.delete(actor.caller.orgId, id, actor.caller.memberId)
            ?: throw EnvironmentVariantNotFoundException()
        journal(audit, actor, "environment.variant.personal.deleted", deleted)
    }
}

/**
 * A member's effective environment values, decrypted: a trusted server-side port, bound to no route by itself. Its
 * callers act for that member (`GET /api/v1/environment`) or for a session that runs for them (the runner), and never
 * log its result. It reads the database on every call, so a change on any replica applies at the next resolution.
 */
fun interface ResolveEnvironmentUseCase {
    /**
     * Resolves them.
     *
     * @param orgId The organization.
     * @param memberId The member.
     *
     * @return The values; none for a member who is no longer active.
     */
    suspend operator fun invoke(orgId: Uuid, memberId: Uuid): EnvironmentValues
}

/**
 * [ResolveEnvironmentUseCase] on the repository: a value that does not open is withheld and named in `unavailable`,
 * the others are still served.
 *
 * @param repository The storage.
 * @param cipher The secrets key.
 * @param members The organization's members.
 */
class ResolveEnvironmentUseCaseImpl(
    private val repository: EnvironmentRepository,
    private val cipher: SecretCipher,
    private val members: MemberDirectory,
) : ResolveEnvironmentUseCase {
    override suspend fun invoke(orgId: Uuid, memberId: Uuid): EnvironmentValues {
        if (!members.isActiveMember(orgId, memberId.toString())) return EnvironmentValues.NONE
        val values = mutableMapOf<String, SecretValue>()
        val unavailable = mutableListOf<String>()
        for (assigned in repository.assignedValues(orgId, memberId)) {
            val binding = assigned.binding
            val envelope = assigned.envelope
            val plaintext = if (binding == null || envelope == null) {
                null
            } else {
                cipher.open(envelope, context(orgId, assigned.variantId, memberId, binding))
            }
            if (plaintext == null) {
                unavailable += assigned.variable
            } else {
                values[assigned.variable] = SecretValue(plaintext.decodeToString())
            }
        }
        return EnvironmentValues(values.toMap(), unavailable.sorted())
    }
}

// The context a value is sealed with: the variant's, and the member's for a per-member one.
private fun context(orgId: Uuid, variantId: Uuid, memberId: Uuid, binding: EnvironmentBinding): String =
    when (binding) {
        EnvironmentBinding.SHARED -> environmentContext(orgId, variantId)
        EnvironmentBinding.PER_MEMBER -> environmentContext(orgId, variantId, memberId)
    }

private const val VARIANT_SUBJECT = "environment_variant"
private const val MEMBER_SUBJECT = "member"

private suspend fun sealNew(
    cipher: SecretCipher,
    orgId: Uuid,
    ownerMemberId: Uuid?,
    variant: NewEnvironmentVariant,
    clock: Clock,
): StoredEnvironmentVariant {
    EnvironmentRules.requireVariable(variant.variable)
    EnvironmentRules.requireName(variant.name)
    val value = variant.value
    when {
        variant.binding == EnvironmentBinding.SHARED && value == null ->
            throw InvalidEnvironmentVariantException("A shared variant needs a value.")
        variant.binding == EnvironmentBinding.PER_MEMBER && value != null ->
            throw InvalidEnvironmentVariantException(
                "A per-member variant keeps each member's value on their assignment, not on the variant.",
            )
    }
    value?.let(EnvironmentRules::requireValue)
    val now = clock.now()
    val id = Uuid.random()
    val envelope = value?.let { cipher.seal(it.reveal().encodeToByteArray(), environmentContext(orgId, id)) }
    return StoredEnvironmentVariant(
        EnvironmentVariant(
            id = id,
            orgId = orgId,
            variable = variant.variable,
            name = variant.name.trim(),
            binding = variant.binding,
            ownerMemberId = ownerMemberId,
            hasValue = envelope != null,
            version = 1,
            createdAt = now,
            updatedAt = now,
        ),
        envelope,
    )
}

// The refusals of a change: a value on a per-member variant, a stale version, a taken name.
@Suppress("ThrowsCount")
private suspend fun change(
    repository: EnvironmentRepository,
    cipher: SecretCipher,
    current: EnvironmentVariant,
    update: EnvironmentVariantUpdate,
    clock: Clock,
): EnvironmentVariant {
    update.name?.let(EnvironmentRules::requireName)
    val value = update.value
    if (value != null && current.binding == EnvironmentBinding.PER_MEMBER) {
        throw InvalidEnvironmentVariantException(
            "A per-member variant keeps each member's value on their assignment, not on the variant.",
        )
    }
    value?.let(EnvironmentRules::requireValue)
    val envelope = value?.let {
        cipher.seal(it.reveal().encodeToByteArray(), environmentContext(current.orgId, current.id))
    }
    val outcome = repository.update(
        current.orgId,
        current.id,
        current.ownerMemberId,
        VariantChange(update.expectedVersion, update.name?.trim(), envelope, clock.now()),
    )
    return when (outcome) {
        is UpdateVariantOutcome.Updated -> outcome.variant
        UpdateVariantOutcome.NotFound -> throw EnvironmentVariantNotFoundException()
        UpdateVariantOutcome.VersionConflict -> throw VariantVersionConflictException()
        UpdateVariantOutcome.NameTaken -> throw VariantExistsException()
    }
}

private suspend fun journal(
    audit: OrgAuditTrail,
    actor: EnvironmentActor,
    event: String,
    variant: EnvironmentVariant,
    vararg facts: Pair<String, Any?>,
) {
    val details = auditDetails("variable" to variant.variable, "binding" to variant.binding.wire, *facts)
    audit.record(
        OrgAuditRecord(actor.caller.orgId, actor.principal, event, VARIANT_SUBJECT, variant.id.toString(), details),
    )
}

private suspend fun recordMember(
    audit: OrgAuditTrail,
    actor: EnvironmentActor,
    event: String,
    memberId: Uuid,
    variable: String,
) {
    audit.record(
        OrgAuditRecord(
            actor.caller.orgId,
            actor.principal,
            event,
            MEMBER_SUBJECT,
            memberId.toString(),
            auditDetails("variable" to variable),
        ),
    )
}

private suspend fun requireMember(members: MemberDirectory, orgId: Uuid, memberId: Uuid) {
    if (!members.isActiveMember(orgId, memberId.toString())) throw MemberNotFoundException()
}
