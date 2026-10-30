// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs

import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.orgs.domain.AcceptInvitationUseCaseImpl
import dev.ostrom.server.orgs.domain.AlreadyMemberException
import dev.ostrom.server.orgs.domain.CreateInviteLinkUseCaseImpl
import dev.ostrom.server.orgs.domain.INVITATION_LIFETIME
import dev.ostrom.server.orgs.domain.InvalidOrgRequestException
import dev.ostrom.server.orgs.domain.InvitationNotFoundException
import dev.ostrom.server.orgs.domain.InviteByEmailUseCaseImpl
import dev.ostrom.server.orgs.domain.InviteRequest
import dev.ostrom.server.orgs.domain.ListInvitationsUseCaseImpl
import dev.ostrom.server.orgs.domain.Member
import dev.ostrom.server.orgs.domain.NotAllowedException
import dev.ostrom.server.orgs.domain.Org
import dev.ostrom.server.orgs.domain.OrgRateLimitedException
import dev.ostrom.server.orgs.domain.Person
import dev.ostrom.server.orgs.domain.PreviewInvitationUseCaseImpl
import dev.ostrom.server.orgs.domain.RevokeInvitationUseCaseImpl
import dev.ostrom.server.orgs.domain.SeatLimitException
import dev.ostrom.server.orgs.fakes.OrgsWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class InvitationUseCasesTest {
    private val world = OrgsWorld()
    private val org =
        Org(Uuid.random(), "acme", "Acme", null, null, world.clock.now).also {
            world.store.orgs[it.id] = it
        }
    private val ada = world.people.add("ada").also { join(it, OrgRole.OWNER) }
    private val bob = world.people.add("bob").also { join(it, OrgRole.ADMIN) }
    private val carl = world.people.add("carl").also { join(it, OrgRole.MEMBER) }

    private val invite =
        InviteByEmailUseCaseImpl(
            world.orgs,
            world.members,
            world.invitations,
            world.people,
            world.services,
            world.clock,
        )
    private val link =
        CreateInviteLinkUseCaseImpl(
            world.orgs,
            world.members,
            world.invitations,
            world.services,
            world.clock,
        )
    private val list =
        ListInvitationsUseCaseImpl(world.orgs, world.members, world.invitations, world.clock)
    private val revoke =
        RevokeInvitationUseCaseImpl(
            world.orgs,
            world.members,
            world.invitations,
            world.audit,
            world.clock,
        )
    private val preview =
        PreviewInvitationUseCaseImpl(world.orgs, world.invitations, world.tokens, world.clock)
    private val accept =
        AcceptInvitationUseCaseImpl(
            world.orgs,
            world.members,
            world.invitations,
            world.people,
            world.services,
            world.clock,
        )

    private fun join(person: Person, role: OrgRole) {
        val member = Member(Uuid.random(), org.id, person.id, role, world.clock.now)
        world.store.members[member.id] = member
    }

    private fun tokenOf(link: String) = link.substringAfter("#token=")

    @Test
    fun anAdminInvitesNewAddressesOnceEach() = runTest {
        // A member without an e-mail address does not hide anyone.
        join(world.people.add("nomail", null), OrgRole.MEMBER)
        val invited =
            invite(
                org.id,
                bob.id,
                listOf(
                    InviteRequest(" Dana@Example.com ", OrgRole.MEMBER),
                    InviteRequest("dana@example.com", OrgRole.APPROVER),
                    InviteRequest("carl@example.com", OrgRole.MEMBER),
                ),
            )

        assertEquals(listOf("dana@example.com"), invited)
        assertEquals(
            "dana@example.com" to "https://app.test/invite#token=token-1",
            world.mailer.sent.single(),
        )
        assertEquals(
            "org.invitation.created" to mapOf("email" to "dana@example.com", "role" to "member"),
            world.audit.events.single(),
        )
        val pending = list(org.id, bob.id).single()
        assertEquals(1, pending.maxUses)
        assertEquals(world.clock.now + INVITATION_LIFETIME, pending.expiresAt)
        assertEquals(
            emptyList(),
            invite(org.id, bob.id, listOf(InviteRequest("dana@example.com", OrgRole.MEMBER))),
        )
    }

    @Test
    fun invitationsStayWithinTheCallersRoleTheSizeAndTheSeats() = runTest {
        val one = listOf(InviteRequest("dana@example.com", OrgRole.MEMBER))
        assertFailsWith<InvalidOrgRequestException> { invite(org.id, bob.id, emptyList()) }
        assertFailsWith<InvalidOrgRequestException> {
            invite(
                org.id,
                bob.id,
                (1..51).map { InviteRequest("p$it@example.com", OrgRole.MEMBER) },
            )
        }
        assertFailsWith<InvalidOrgRequestException> {
            invite(org.id, bob.id, listOf(InviteRequest("nope", OrgRole.MEMBER)))
        }
        assertFailsWith<NotAllowedException> { invite(org.id, carl.id, one) }
        assertFailsWith<NotAllowedException> {
            invite(org.id, bob.id, listOf(InviteRequest("d@example.com", OrgRole.ADMIN)))
        }
        assertFailsWith<NotAllowedException> {
            invite(org.id, ada.id, listOf(InviteRequest("d@example.com", OrgRole.OWNER)))
        }
        assertEquals(
            listOf("d@example.com"),
            invite(org.id, ada.id, listOf(InviteRequest("d@example.com", OrgRole.ADMIN))),
        )

        world.seats.limit = 4
        assertFailsWith<SeatLimitException> { invite(org.id, bob.id, one) }
        world.seats.limit = 5
        world.limiter.allow = false
        assertFailsWith<OrgRateLimitedException> { invite(org.id, bob.id, one) }
    }

    @Test
    fun anInviterWithoutAnAccountIsADefect() = runTest {
        world.people.people.remove(bob.id)
        assertFailsWith<IllegalStateException> {
            invite(org.id, bob.id, listOf(InviteRequest("d@example.com", OrgRole.MEMBER)))
        }
    }

    @Test
    fun theInvitedAddressAcceptsOnceAfterPreviewing() = runTest {
        invite(org.id, bob.id, listOf(InviteRequest("dana@example.com", OrgRole.APPROVER)))
        val token = tokenOf(world.mailer.sent.single().second)
        val dana = world.people.add("dana", "dana@example.com")
        val impostor = world.people.add("eve", "eve@example.com")

        val shown = preview(token)
        assertEquals(org, shown.org)
        assertEquals("dana@example.com", shown.invitation.email)
        assertFailsWith<InvitationNotFoundException> { accept(impostor.id, token) }
        val joined = accept(dana.id, token)

        assertEquals(OrgRole.APPROVER, joined.member.role)
        assertEquals(joined.member, world.members.findByUser(org.id, dana.id))
        assertEquals("org.invitation.accepted", world.audit.events.last().first)
        assertFailsWith<InvitationNotFoundException> { accept(dana.id, token) }
        assertFailsWith<InvitationNotFoundException> { preview(token) }
    }

    @Test
    fun aLinkBringsInUpToItsLimit() = runTest {
        assertFailsWith<InvalidOrgRequestException> { link(org.id, bob.id, OrgRole.MEMBER, 0) }
        assertFailsWith<InvalidOrgRequestException> { link(org.id, bob.id, OrgRole.MEMBER, 101) }
        assertFailsWith<NotAllowedException> { link(org.id, carl.id, OrgRole.MEMBER, 2) }
        assertFailsWith<NotAllowedException> { link(org.id, bob.id, OrgRole.ADMIN, 2) }
        val issued = link(org.id, bob.id, OrgRole.MEMBER, 1)
        assertEquals("https://app.test/invite#token=token-1", issued.url)
        assertEquals(null, issued.invitation.email)
        assertEquals(
            "org.invitation.created" to mapOf("role" to "member", "max_uses" to "1"),
            world.audit.events.single(),
        )

        val dana = world.people.add("dana")
        val eve = world.people.add("eve")
        assertFailsWith<AlreadyMemberException> { accept(carl.id, tokenOf(issued.url)) }
        accept(dana.id, tokenOf(issued.url))
        assertFailsWith<InvitationNotFoundException> { accept(eve.id, tokenOf(issued.url)) }
        world.limiter.allow = false
        assertFailsWith<OrgRateLimitedException> { link(org.id, bob.id, OrgRole.MEMBER, 1) }
    }

    @Test
    fun anAcceptanceNeedsASeatAndLosesARace() = runTest {
        val issued = link(org.id, bob.id, OrgRole.MEMBER, 10)
        val dana = world.people.add("dana")
        world.seats.limit = 3
        assertFailsWith<SeatLimitException> { accept(dana.id, tokenOf(issued.url)) }
        world.seats.limit = 5
        world.invitations.loseNextRace = true
        assertFailsWith<InvitationNotFoundException> { accept(dana.id, tokenOf(issued.url)) }
        assertEquals(OrgRole.MEMBER, accept(dana.id, tokenOf(issued.url)).member.role)
    }

    @Test
    fun adminsListAndRevokeLiveInvitations() = runTest {
        val first = link(org.id, bob.id, OrgRole.MEMBER, 3)
        world.clock.now += 1.seconds
        val second = link(org.id, bob.id, OrgRole.BILLING, 3)

        assertEquals(listOf(second.invitation, first.invitation), list(org.id, ada.id))
        assertFailsWith<NotAllowedException> { list(org.id, carl.id) }
        assertFailsWith<NotAllowedException> { revoke(org.id, carl.id, first.invitation.id) }
        revoke(org.id, bob.id, first.invitation.id)
        assertEquals("org.invitation.revoked", world.audit.events.last().first)
        assertFailsWith<InvitationNotFoundException> { revoke(org.id, bob.id, first.invitation.id) }
        assertEquals(listOf(second.invitation), list(org.id, bob.id))
        assertFailsWith<InvitationNotFoundException> { preview(tokenOf(first.url)) }

        world.clock.now += INVITATION_LIFETIME
        assertEquals(emptyList(), list(org.id, bob.id))
    }

    @Test
    fun aMalformedTokenOrADeletedOrganizationLeadsNowhere() = runTest {
        val issued = link(org.id, bob.id, OrgRole.MEMBER, 3)
        assertFailsWith<InvitationNotFoundException> { preview("") }
        assertFailsWith<InvitationNotFoundException> { preview("x".repeat(129)) }
        world.store.orgs.remove(org.id)
        assertFailsWith<InvitationNotFoundException> { preview(tokenOf(issued.url)) }
        assertTrue(world.store.invitations.isNotEmpty())
    }
}
