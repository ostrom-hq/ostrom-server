// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.orgs

import run.ostrom.server.identity.domain.SignUpGrant
import run.ostrom.server.identity.domain.SignUpMode
import run.ostrom.server.identity.domain.SignUpPolicy
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.orgs.domain.Invitation
import run.ostrom.server.orgs.fakes.OrgsWorld
import run.ostrom.server.orgs.infrastructure.OrgSignUpInvitations
import run.ostrom.server.orgs.infrastructure.SignUpOrgCreationGate
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/** TC-CODE-AUTH-2: the organizations' invitations open an invite-only instance, and its policy gates org creation. */
class SignUpAdaptersTest {
    private val world = OrgsWorld()

    @Test
    fun aLiveInvitationLetsItsPersonGetAnAccountAndARevokedOneDoesNot() = runTest {
        val now = world.clock.now
        val invitation = Invitation(
            Uuid.random(), Uuid.random(), null, OrgRole.MEMBER, Uuid.random(), now, now + 7.days, null, 0, null,
        )
        world.invitations.create(invitation, world.tokens.hash("live"))
        world.invitations.create(
            invitation.copy(id = Uuid.random(), revokedAt = now),
            world.tokens.hash("revoked"),
        )
        val live = OrgSignUpInvitations(world.invitations, world.tokens, world.clock)

        assertTrue(live.isLive("live"))
        assertFalse(live.isLive("revoked"))
        assertFalse(live.isLive("unknown"))
        world.clock.now += 8.days
        assertFalse(live.isLive("live"), "Expired")
    }

    @Test
    fun theGateAsksTheInstancesPolicy() = runTest {
        val setupPerson = Uuid.random()
        val policy = object : SignUpPolicy {
            override val mode = SignUpMode.INVITE

            override suspend fun admits(identity: VerifiedIdentity, grant: SignUpGrant) = false

            override suspend fun claim(grant: SignUpGrant, userId: Uuid) = Unit

            override suspend fun accepts(token: String) = false

            override suspend fun mayCreateOrgs(userId: Uuid) = userId == setupPerson
        }
        val gate = SignUpOrgCreationGate(policy)

        assertEquals(listOf(true, false), listOf(gate.opensTo(setupPerson), gate.opensTo(Uuid.random())))
    }
}
