// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import kotlinx.coroutines.test.runTest
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.GitHubOwnerOnlyException
import run.ostrom.server.runners.domain.InvalidEffectiveMemberException
import run.ostrom.server.runners.domain.OnBehalfForbiddenException
import run.ostrom.server.runners.domain.ResolveCloudActorUseCaseImpl
import run.ostrom.server.runners.domain.TransferRecipient
import run.ostrom.server.runners.domain.TransferRecipients
import run.ostrom.server.runners.fakes.FakeTransferRecipients
import run.ostrom.server.runners.fakes.ORG_A
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** `Ostrom-On-Behalf-Of` (`runners.cloud.onbehalf.v1`), TeamCode's `CloudSessionService.actor`. */
class ResolveCloudActorUseCaseTest {
    private val members = FakeTransferRecipients(
        TransferRecipient(ALICE, "alice", false),
        TransferRecipient(BOB, "bob", false),
        TransferRecipient(CAROL, "carol", true),
        TransferRecipient(DANA, "dana", true),
    )
    private val resolve = ResolveCloudActorUseCaseImpl(members)
    private val alice = Caller(ORG_A, ALICE)
    private val dana = Caller(ORG_A, DANA, admin = true)
    private val person = principal(PrincipalKind.USER)

    private fun principal(kind: PrincipalKind) = AuthenticatedPrincipal("s", kind, emptySet(), credentialId = "cred-1")

    @Test
    fun withoutTheHeaderOrNamingThemselvesTheCallerActsForThemselvesWithTheirCredential() = runTest {
        for (reference in listOf(null, DANA, "dana")) {
            val actor = resolve(dana, person, reference)
            assertEquals(DANA, actor.memberId)
            assertEquals(DANA, actor.initiatorId)
            assertEquals("cred-1", actor.credentialId)
            assertSame(person, actor.principal)
            assertFalse(actor.onBehalf)
            assertTrue(actor.memberAdmin)
        }
    }

    @Test
    fun aMemberNamesOnlyThemselvesByIdOrLogin() = runTest {
        assertEquals(ALICE, resolve(alice, person, "alice").memberId)
        assertEquals(ALICE, resolve(alice, person, ALICE).memberId)
        assertFailsWith<OnBehalfForbiddenException> { resolve(alice, person, "bob") }
        assertFailsWith<OnBehalfForbiddenException> { resolve(alice, person, BOB) }
        // Whether the member exists is never told to someone who may not name them.
        assertFailsWith<OnBehalfForbiddenException> { resolve(alice, person, "nobody") }
    }

    @Test
    fun anAdministratorSignedInOrTheirServiceKeyActsForAnotherMember() = runTest {
        for (kind in listOf(PrincipalKind.USER, PrincipalKind.SERVICE)) {
            val actor = resolve(dana, principal(kind), "bob")
            assertEquals(BOB, actor.memberId)
            assertEquals(DANA, actor.initiatorId)
            assertTrue(actor.onBehalf)
            assertTrue(actor.admin, "the call keeps the caller's rights")
            assertFalse(actor.memberAdmin, "the owner's role is bob's")
            assertEquals("cred-1", actor.attribution.credentialId)
            assertEquals(BOB, actor.attribution.effectiveMemberId)
        }
        assertTrue(resolve(dana, person, CAROL).memberAdmin)
    }

    @Test
    fun anAgentOrADeviceNeverActsForAnotherMemberEvenAnAdministrators() = runTest {
        for (kind in listOf(PrincipalKind.AGENT, PrincipalKind.DEVICE)) {
            assertFailsWith<OnBehalfForbiddenException> { resolve(dana, principal(kind), "bob") }
            assertEquals(DANA, resolve(dana, principal(kind), "dana").memberId)
        }
    }

    @Test
    fun theEffectiveMemberMustBeAnActiveMemberOfTheOrganization() = runTest {
        // A login nobody has, and an id of no member of the organization (one who left, another organization's).
        for (reference in listOf("ghost", "00000000-0000-0000-0000-0000000000ff")) {
            assertFailsWith<InvalidEffectiveMemberException> { resolve(dana, person, reference) }
        }
        val left = FakeTransferRecipients(TransferRecipient(DANA, "dana", true))
        val directory = object : TransferRecipients by left {
            override suspend fun find(orgId: Uuid, reference: String): String? = BOB
        }
        val resolveLeft = ResolveCloudActorUseCaseImpl(directory)
        assertFailsWith<InvalidEffectiveMemberException> { resolveLeft(dana, person, "bob") }
    }

    @Test
    fun withoutTheOrganizationsMembersTheHeaderIsIgnored() = runTest {
        val ignoring = ResolveCloudActorUseCaseImpl(null)
        assertEquals(ALICE, ignoring(alice, person, "bob").memberId)
        assertEquals(ALICE, ignoring.owner(alice, person, "bob") { GitHubOwnerOnlyException() }.memberId)
    }

    @Test
    fun anOwnersOwnWriteNamesNobodyElse() = runTest {
        for (reference in listOf(null, DANA, "dana")) {
            val owner = resolve.owner(dana, person, reference) { GitHubOwnerOnlyException() }
            assertEquals(DANA, owner.memberId)
            assertEquals("cred-1", owner.credentialId)
        }
        // An administrator too: the owner decides alone.
        for (reference in listOf("bob", "ghost")) {
            assertFailsWith<GitHubOwnerOnlyException> {
                resolve.owner(dana, person, reference) { GitHubOwnerOnlyException() }
            }
        }
    }

    private companion object {
        const val ALICE = "00000000-0000-0000-0000-0000000000a1"
        const val BOB = "00000000-0000-0000-0000-0000000000b2"
        const val CAROL = "00000000-0000-0000-0000-0000000000c3"
        const val DANA = "00000000-0000-0000-0000-0000000000d4"
    }
}
