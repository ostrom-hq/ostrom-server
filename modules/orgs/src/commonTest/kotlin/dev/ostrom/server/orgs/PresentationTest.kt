// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.config.MapConfigSource
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.orgs.domain.Member
import dev.ostrom.server.orgs.domain.Membership
import dev.ostrom.server.orgs.domain.Org
import dev.ostrom.server.orgs.infrastructure.invitationMail
import dev.ostrom.server.orgs.presentation.MyOrganizationResponse
import dev.ostrom.server.orgs.presentation.OrgsConfig
import dev.ostrom.server.orgs.presentation.key
import dev.ostrom.server.orgs.presentation.roleOf
import dev.ostrom.server.orgs.presentation.toMyOrganizationResponse
import dev.ostrom.server.orgs.presentation.userId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class PresentationTest {
    @Test
    fun invitationLinksLeadToTheConsoleWithTheTokenInTheFragment() {
        assertEquals(
            "http://localhost:5173/invite#token=t",
            OrgsConfig.from(MapConfigSource(emptyMap())).invitationUrl("t"),
        )
        assertEquals(
            "http://localhost:5173",
            OrgsConfig.from(MapConfigSource(mapOf("OSTROM_CONSOLE_URL" to "  "))).consoleUrl,
        )
        assertEquals(
            "https://app.ostrom.run/invite#token=t",
            OrgsConfig.from(
                    MapConfigSource(mapOf("OSTROM_CONSOLE_URL" to "https://app.ostrom.run/"))
                )
                .invitationUrl("t"),
        )
    }

    @Test
    fun theInvitationMailIsPlainEscapedHtml() {
        val mail =
            invitationMail("d<a>@x.com", "A&B \"Co\"", "Ada's", "https://app.test/invite#token=a&b")

        assertEquals("Ada's invited you to A&B \"Co\" on Ostrom", mail.subject)
        assertTrue("A&amp;B &quot;Co&quot;" in mail.html, mail.html)
        assertTrue("Ada&#39;s invited d&lt;a&gt;@x.com" in mail.html, mail.html)
        assertTrue("href=\"https://app.test/invite#token=a&amp;b\"" in mail.html)
        assertTrue("works for 7 days" in mail.html)
        assertFalse("<img" in mail.html)
        assertTrue(mail.text.contains("https://app.test/invite#token=a&b"))
        assertEquals("d<a>@x.com", mail.to)
    }

    @Test
    fun rolesReadAndWriteTheirApiNames() {
        OrgRole.entries.forEach { assertEquals(it, roleOf(it.key())) }
        assertFailsWith<OstromException> { roleOf("OWNER") }
    }

    @Test
    fun onlyAPersonHasAUserId() {
        val id = Uuid.random()
        assertEquals(
            id,
            AuthenticatedPrincipal(id.toString(), PrincipalKind.USER, emptySet()).userId(),
        )
        assertFailsWith<OstromException> {
            AuthenticatedPrincipal(id.toString(), PrincipalKind.SERVICE, emptySet()).userId()
        }
        assertFailsWith<OstromException> {
            AuthenticatedPrincipal("x", PrincipalKind.USER, emptySet()).userId()
        }
    }

    @Test
    fun anOrganizationToChooseShowsItsGitHubAvatarOnlyWhenLinked() {
        val at = Instant.fromEpochSeconds(0)
        val org = Org(Uuid.random(), "acme", "Acme", null, null, at)
        val membership = Membership(org, Member(Uuid.random(), org.id, Uuid.random(), OrgRole.ADMIN, at))
        assertEquals(
            MyOrganizationResponse(org.id.toString(), "acme", "Acme", "", "admin"),
            membership.toMyOrganizationResponse(),
        )
        val linked = membership.copy(org = org.copy(githubOrgId = 42))
        assertEquals("https://avatars.githubusercontent.com/u/42", linked.toMyOrganizationResponse().avatarUrl)
    }
}

