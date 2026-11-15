// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.secrets

import run.ostrom.server.kernel.audit.DiscardingOrgAuditTrail
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.secrets.domain.AssignedEnvironmentValue
import run.ostrom.server.secrets.domain.AssignmentSource
import run.ostrom.server.secrets.domain.BulkAssignment
import run.ostrom.server.secrets.domain.CreateVariantOutcome
import run.ostrom.server.secrets.domain.EnvironmentActor
import run.ostrom.server.secrets.domain.EnvironmentAssignment
import run.ostrom.server.secrets.domain.EnvironmentBinding
import run.ostrom.server.secrets.domain.EnvironmentExclusion
import run.ostrom.server.secrets.domain.EnvironmentRepository
import run.ostrom.server.secrets.domain.EnvironmentValueCheckImpl
import run.ostrom.server.secrets.domain.EnvironmentVariant
import run.ostrom.server.secrets.domain.EnvironmentVariantNotFoundException
import run.ostrom.server.secrets.domain.EnvironmentVariantUpdate
import run.ostrom.server.secrets.domain.ManageEnvironmentVariantsUseCaseImpl
import run.ostrom.server.secrets.domain.NewEnvironmentVariant
import run.ostrom.server.secrets.domain.PersonalEnvironmentVariantsUseCaseImpl
import run.ostrom.server.secrets.domain.SecretValue
import run.ostrom.server.secrets.domain.StoredEnvironmentVariant
import run.ostrom.server.secrets.domain.UpdateVariantOutcome
import run.ostrom.server.secrets.domain.VariantChange
import run.ostrom.server.secrets.domain.VariantExistsException
import run.ostrom.server.secrets.domain.VariantLimitException
import run.ostrom.server.secrets.domain.environmentContext
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The outcomes of the storage that only a race reaches, on a hand-written repository. */
class EnvironmentUseCasesTest {
    private val now = Instant.parse("2026-11-13T00:00:00Z")
    private val clock = object : Clock {
        override fun now() = now
    }
    private val cipher = AesGcmSecretCipher(ByteArray(32) { 7 })
    private val members = object : MemberDirectory {
        override suspend fun isActiveMember(orgId: Uuid, memberId: String) = true
    }
    private val admin = EnvironmentActor(
        TestCallers.adminA,
        AuthenticatedPrincipal("admin-a", PrincipalKind.USER, emptySet()),
    )
    private val variant = EnvironmentVariant(
        Uuid.random(), TestCallers.orgA, "TOKEN", "t", EnvironmentBinding.SHARED, null, true, 1, now, now,
    )

    @Test
    fun aTeamAtItsLimitIsRefused() = runTest {
        val repository = Storage(created = CreateVariantOutcome.LIMIT)
        val variants = variants(repository)
        assertFailsWith<VariantLimitException> {
            variants.create(admin, NewEnvironmentVariant("TOKEN", "t", EnvironmentBinding.SHARED, SecretValue("v")))
        }
    }

    @Test
    fun aVariantDeletedBetweenTheReadAndTheChangeIsNotFound() = runTest {
        val repository = Storage(stored = StoredEnvironmentVariant(variant, null))
        val variants = variants(repository)
        assertFailsWith<EnvironmentVariantNotFoundException> {
            variants.update(admin, variant.id, EnvironmentVariantUpdate(1, "x", null))
        }
    }

    @Test
    fun aPersonalNameTakenByTheMembersOwnVariantIsRefused() = runTest {
        val repository = Storage(created = CreateVariantOutcome.NAME_TAKEN)
        val personal = PersonalEnvironmentVariantsUseCaseImpl(repository, cipher, DiscardingOrgAuditTrail(), clock)
        assertFailsWith<VariantExistsException> {
            personal.create(admin, NewEnvironmentVariant("TOKEN", "t", EnvironmentBinding.SHARED, SecretValue("v")))
        }
    }

    @Test
    fun aDirectoryThatCannotListTheMembersGrantsNobodyInBulk() = runTest {
        val repository = Storage(stored = StoredEnvironmentVariant(variant, null))
        val result = variants(repository).bulkAssign(admin, variant.id)
        assertEquals(emptyList(), repository.bulkMembers)
        assertEquals(0, result.assigned)
    }

    @Test
    fun theValueCheckSaysWhetherAStoredValueEqualsAnother() = runTest {
        val orgId = TestCallers.orgA
        val member = Uuid.random()
        val shared = cipher.seal("v-1".encodeToByteArray(), environmentContext(orgId, variant.id))
        val mine = cipher.seal("m-1".encodeToByteArray(), environmentContext(orgId, variant.id, member))
        val values = listOf(
            AssignedEnvironmentValue("OTHER", Uuid.random(), EnvironmentBinding.PER_MEMBER, mine),
            AssignedEnvironmentValue("TOKEN", variant.id, EnvironmentBinding.SHARED, shared),
            AssignedEnvironmentValue("TOKEN", variant.id, EnvironmentBinding.PER_MEMBER, mine),
        )
        val storage = Storage(stored = StoredEnvironmentVariant(variant, shared), values = values)
        val check = EnvironmentValueCheckImpl(storage, cipher)
        assertTrue(check.matches(orgId, variant.id, null, SecretValue("v-1")))
        assertFalse(check.matches(orgId, variant.id, null, SecretValue("v-2")))
        assertTrue(check.matches(orgId, variant.id, member, SecretValue("m-1")))
        assertFalse(check.matches(orgId, variant.id, member, SecretValue("m-2")))
        // A value sealed for another member never opens for this one.
        assertFalse(check.matches(orgId, variant.id, Uuid.random(), SecretValue("m-1")))
        assertFalse(check.matches(orgId, Uuid.random(), member, SecretValue("m-1")))
        val empty = EnvironmentValueCheckImpl(Storage(stored = StoredEnvironmentVariant(variant, null)), cipher)
        assertFalse(empty.matches(orgId, variant.id, null, SecretValue("v-1")))
        assertFalse(EnvironmentValueCheckImpl(Storage(), cipher).matches(orgId, variant.id, null, SecretValue("v-1")))
        val foreign = cipher.seal("v-1".encodeToByteArray(), "another context")
        val sealedElsewhere = Storage(stored = StoredEnvironmentVariant(variant, foreign))
        val elsewhere = EnvironmentValueCheckImpl(sealedElsewhere, cipher)
        assertFalse(elsewhere.matches(orgId, variant.id, null, SecretValue("v-1")))
        // A member selected but never given a value has nothing to compare.
        val unset = listOf(AssignedEnvironmentValue("TOKEN", variant.id, EnvironmentBinding.PER_MEMBER, null))
        val unsetCheck = EnvironmentValueCheckImpl(Storage(values = unset), cipher)
        assertFalse(unsetCheck.matches(orgId, variant.id, member, SecretValue("m-1")))
    }

    private fun variants(repository: EnvironmentRepository) =
        ManageEnvironmentVariantsUseCaseImpl(repository, cipher, members, DiscardingOrgAuditTrail(), clock)

    /** Answers what a test sets; any other call is a mistake of the test. */
    @Suppress("TooManyFunctions") // The repository's interface, mostly unused here.
    private class Storage(
        private val created: CreateVariantOutcome = CreateVariantOutcome.CREATED,
        private val stored: StoredEnvironmentVariant? = null,
        private val updated: UpdateVariantOutcome = UpdateVariantOutcome.NotFound,
        private val values: List<AssignedEnvironmentValue> = emptyList(),
    ) : EnvironmentRepository {
        var bulkMembers: List<Uuid>? = null

        override suspend fun get(orgId: Uuid, id: Uuid) = stored

        override suspend fun createTeam(stored: StoredEnvironmentVariant, limit: Int) = created

        override suspend fun createPersonal(stored: StoredEnvironmentVariant, limit: Int) = created

        override suspend fun update(orgId: Uuid, id: Uuid, ownerMemberId: Uuid?, change: VariantChange) = updated

        override suspend fun list(orgId: Uuid, personal: Boolean, ownerMemberId: Uuid?): List<EnvironmentVariant> =
            unused()

        override suspend fun delete(orgId: Uuid, id: Uuid, ownerMemberId: Uuid?): EnvironmentVariant? = unused()

        override suspend fun promote(orgId: Uuid, id: Uuid, updatedAt: Instant, limit: Int): EnvironmentVariant? =
            unused()

        override suspend fun assign(
            orgId: Uuid,
            memberId: Uuid,
            variant: EnvironmentVariant,
            source: AssignmentSource,
            memberEnvelope: String?,
            assignedAt: Instant,
        ): EnvironmentAssignment? = unused()

        override suspend fun unassign(orgId: Uuid, memberId: Uuid, variable: String): Boolean = unused()

        override suspend fun bulkAssign(
            orgId: Uuid,
            variant: EnvironmentVariant,
            memberIds: List<Uuid>,
            assignedAt: Instant,
        ): BulkAssignment {
            bulkMembers = memberIds
            return BulkAssignment(memberIds.size, 0, 0, emptyList())
        }

        override suspend fun exclude(orgId: Uuid, memberId: Uuid, variable: String): Boolean = unused()

        override suspend fun include(orgId: Uuid, memberId: Uuid, variable: String): Boolean = unused()

        override suspend fun assignments(orgId: Uuid, memberId: Uuid?): List<EnvironmentAssignment> = unused()

        override suspend fun exclusions(orgId: Uuid, memberId: Uuid?): List<EnvironmentExclusion> = unused()

        override suspend fun assignedValues(orgId: Uuid, memberId: Uuid): List<AssignedEnvironmentValue> = values

        private fun unused(): Nothing = error("Not used by this test.")
    }
}
