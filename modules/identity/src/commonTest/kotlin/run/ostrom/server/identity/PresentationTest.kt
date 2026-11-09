// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.identity

import run.ostrom.server.identity.domain.ApiToken
import run.ostrom.server.identity.domain.AuditEntry
import run.ostrom.server.identity.domain.AuditEvent
import run.ostrom.server.identity.domain.DevicePollResult
import run.ostrom.server.identity.domain.IssuedToken
import run.ostrom.server.identity.domain.Profile
import run.ostrom.server.identity.domain.SessionRequiredException
import run.ostrom.server.identity.domain.TokenKind
import run.ostrom.server.identity.domain.User
import run.ostrom.server.identity.domain.trimmedOrNull
import run.ostrom.server.identity.presentation.AuthTokenResponse
import run.ostrom.server.identity.presentation.MeDevResponse
import run.ostrom.server.identity.presentation.MeMemberResponse
import run.ostrom.server.identity.presentation.MeOrganizationResponse
import run.ostrom.server.identity.presentation.MeResponse
import run.ostrom.server.identity.presentation.credentialUuid
import run.ostrom.server.identity.presentation.currentOrganization
import run.ostrom.server.identity.presentation.toAccountResponse
import run.ostrom.server.identity.presentation.toAuditEventResponse
import run.ostrom.server.identity.presentation.toAuditEventsResponse
import run.ostrom.server.identity.presentation.toAuthTokenResponse
import run.ostrom.server.identity.presentation.toMeResponse
import run.ostrom.server.identity.presentation.userId
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgProfile
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
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
        val acme = OrgProfile(known, "acme", "Acme", "https://avatars.githubusercontent.com/u/7", linkedToGitHub = true)
        val resolver = object : PrincipalOrgResolver {
            override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
                principal.namedOrg?.let(Uuid::parseOrNull)?.takeIf { it != Uuid.NIL }?.let {
                    OrgPrincipal(it, member, OrgRole.ADMIN)
                }

            override suspend fun profile(orgId: Uuid): OrgProfile? = acme.takeIf { orgId == known }
        }
        val caller = AuthenticatedPrincipal(Uuid.random().toString(), PrincipalKind.USER, setOf("user:*"))

        val current = resolver.currentOrganization(caller.copy(namedOrg = known.toString()))
        assertEquals(OrgPrincipal(known, member, OrgRole.ADMIN) to acme, current)
        assertNull(resolver.currentOrganization(caller.copy(namedOrg = unknown.toString())), "No profile")
        assertNull(resolver.currentOrganization(caller.copy(namedOrg = Uuid.NIL.toString())), "No membership")

        val user = User(Uuid.random(), "ada", "Ada", "ada@acme.test", null, Instant.fromEpochSeconds(0))
        val me = Profile(user, emptyList(), listOf("google", "github")).toMeResponse(setOf("b", "a"), null, current)
        assertEquals(
            MeResponse(
                dev = MeDevResponse("ada", verified = true, via = "github", organizationId = known.toString()),
                member = MeMemberResponse(member.toString(), "ada", "Ada", "", "ada@acme.test", "admin"),
                organization = MeOrganizationResponse(known.toString(), "Acme", acme.avatarUrl, "github-app"),
                capabilities = listOf("a", "b"),
            ),
            me,
        )
        val pictured = Profile(user.copy(avatarUrl = "https://a.test/ada.png"), emptyList(), emptyList())
        assertEquals("https://a.test/ada.png", pictured.toMeResponse(emptySet(), null, current).member?.avatarUrl)
        // An organization linked to no GitHub organization says no connection rather than a false one.
        val unlinked = current?.let { (member, org) -> member to org.copy(avatarUrl = "", linkedToGitHub = false) }
        val alone = pictured.toMeResponse(emptySet(), null, unlinked).organization
        assertEquals(MeOrganizationResponse(known.toString(), "Acme", "", null), alone)
    }

    @Test
    fun meSaysHowTheServerKnowsThePersonGitHubFirst() {
        val user = User(Uuid.random(), "ada", "Ada", null, null, Instant.fromEpochSeconds(0))
        fun via(vararg issuers: String) = Profile(user, emptyList(), issuers.toList()).toMeResponse(
            emptySet(),
            "csrf",
            null,
        ).dev.via
        assertEquals("github", via("email", "github"))
        assertEquals("google", via("google", "oidc:okta"))
        assertEquals("email", via(), "No way in recorded: the e-mail link")
        val account = Profile(user, emptyList(), listOf("github")).toAccountResponse()
        assertEquals(listOf("github"), account.providers)
    }

    @Test
    fun aDeviceTokenWithoutExpiryReadsWithoutOne() {
        val token = ApiToken(
            Uuid.random(), Uuid.random(), null, "laptop", TokenKind.CLI, setOf("runs:read", "profile:read"), "abcd",
            null, Instant.fromEpochSeconds(0), null, null, null,
        )
        val answer = DevicePollResult.Approved(IssuedToken(token, "osc_secret")).toAuthTokenResponse("ada")
        assertEquals(AuthTokenResponse("osc_secret", "ada", null, listOf("profile:read", "runs:read")), answer)
    }
}
