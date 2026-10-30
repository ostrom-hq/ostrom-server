// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.AuditEntry
import dev.ostrom.server.identity.domain.AuditEvent
import dev.ostrom.server.identity.domain.Profile
import dev.ostrom.server.identity.domain.SessionRequiredException
import dev.ostrom.server.identity.domain.User
import dev.ostrom.server.identity.domain.trimmedOrNull
import dev.ostrom.server.identity.presentation.MeMemberResponse
import dev.ostrom.server.identity.presentation.MeOrganizationResponse
import dev.ostrom.server.identity.presentation.credentialUuid
import dev.ostrom.server.identity.presentation.currentOrganization
import dev.ostrom.server.identity.presentation.toAuditEventResponse
import dev.ostrom.server.identity.presentation.toAuditEventsResponse
import dev.ostrom.server.identity.presentation.toProfileResponse
import dev.ostrom.server.identity.presentation.userId
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgProfile
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

/** The mappers and principal helpers of the routes, and the free-text rule they share with the use cases. */
class PresentationTest {
    private fun event(sequence: Long, orgId: Uuid?, actorId: Uuid?) = AuditEvent(
        Uuid.random(), sequence, AuditEntry(orgId, actorId, "a", "t", "1"),
        Instant.parse("2026-10-30T10:00:00Z"), null, "h", "s", "k",
    )

    @Test
    fun anEventShowsItsOrganizationAndActorOnlyWhenItHasThem() {
        val org = Uuid.random()
        val actor = Uuid.random()

        val inOrg = event(1, org, null).toAuditEventResponse()
        val ofAccount = event(2, null, actor).toAuditEventResponse()

        assertEquals(org.toString(), inOrg.orgId)
        assertNull(inOrg.actorId)
        assertNull(ofAccount.orgId)
        assertEquals(actor.toString(), ofAccount.actorId)
    }

    @Test
    fun onlyAFullPageOffersTheNextOne() {
        val page = listOf(event(9, null, null), event(8, null, null))

        assertEquals(8L, page.toAuditEventsResponse(2).nextBefore)
        assertNull(page.toAuditEventsResponse(3).nextBefore)
        assertNull(emptyList<AuditEvent>().toAuditEventsResponse(0).nextBefore)
    }

    @Test
    fun aPrincipalWithoutAUsableUserOrCredentialNeedsASession() {
        val user = Uuid.random()
        val credential = Uuid.random()
        val signedIn = AuthenticatedPrincipal(user.toString(), PrincipalKind.USER, emptySet(), credential.toString())

        assertEquals(user, signedIn.userId())
        assertEquals(credential, signedIn.credentialUuid())
        assertFailsWith<SessionRequiredException> { signedIn.copy(kind = PrincipalKind.AGENT).userId() }
        assertFailsWith<SessionRequiredException> { signedIn.copy(subjectId = "not-an-id").userId() }
        assertFailsWith<SessionRequiredException> { signedIn.copy(credentialId = null).credentialUuid() }
        assertFailsWith<SessionRequiredException> { signedIn.copy(credentialId = "not-an-id").credentialUuid() }
    }

    @Test
    fun freeTextIsTrimmedCutAndBlankMeansNone() {
        assertEquals("Ada", trimmedOrNull("  Ada  ", 10))
        assertEquals("Ad", trimmedOrNull("Ada", 2))
        assertNull(trimmedOrNull("   ", 10))
        assertNull(trimmedOrNull(null, 10))
    }

    @Test
    fun meShowsTheNamedOrganizationOnlyWhenTheCallerIsAMemberOfAKnownOne() = runTest {
        val known = Uuid.random()
        val unknown = Uuid.random()
        val member = Uuid.random()
        val resolver = object : PrincipalOrgResolver {
            override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
                principal.namedOrg?.let(Uuid::parseOrNull)?.takeIf { it != Uuid.NIL }?.let {
                    OrgPrincipal(it, member, OrgRole.ADMIN)
                }

            override suspend fun profile(orgId: Uuid): OrgProfile? =
                OrgProfile(orgId, "acme", "Acme").takeIf { orgId == known }
        }
        val caller = AuthenticatedPrincipal(Uuid.random().toString(), PrincipalKind.USER, setOf("user:*"))

        val current = resolver.currentOrganization(caller.copy(namedOrg = known.toString()))
        assertEquals(
            MeMemberResponse(member.toString(), "admin") to MeOrganizationResponse(known.toString(), "acme", "Acme"),
            current,
        )
        assertNull(resolver.currentOrganization(caller.copy(namedOrg = unknown.toString())), "No profile")
        assertNull(resolver.currentOrganization(caller.copy(namedOrg = Uuid.NIL.toString())), "No membership")

        val user = User(Uuid.random(), "ada", "Ada", null, null, Instant.fromEpochSeconds(0))
        val profile = Profile(user, emptyList(), emptyList()).toProfileResponse(null, current)
        assertEquals("admin", profile.member?.role)
        assertEquals("acme", profile.organization?.slug)
    }
}

