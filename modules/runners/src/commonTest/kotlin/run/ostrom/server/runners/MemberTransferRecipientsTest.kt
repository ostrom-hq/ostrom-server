// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import kotlinx.coroutines.test.runTest
import run.ostrom.server.cloud.domain.CloudRuntime
import run.ostrom.server.cloud.domain.CloudRuntimeSource
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.runners.domain.TransferRecipient
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.infrastructure.MemberTransferRecipients
import run.ostrom.server.runners.infrastructure.RuntimeMemberTransferPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Who a session can be given to, from the organization's members, and whether members may offer. */
class MemberTransferRecipientsTest {
    private val ada = Uuid.random()
    private val bob = Uuid.random()
    private val noLogin = Uuid.random()
    private var roles: Map<String, OrgRole>? = mapOf(
        ada.toString() to OrgRole.OWNER,
        bob.toString() to OrgRole.MEMBER,
        noLogin.toString() to OrgRole.ADMIN,
    )
    private val active = mutableSetOf(ada.toString())
    private val directory = object : MemberDirectory {
        override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean = memberId in active

        override suspend fun roles(orgId: Uuid, limit: Int): Map<String, OrgRole>? = roles
    }
    private val logins = object : MemberLogins {
        private val names = mapOf(ada to "Ada", bob to "bob")

        override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> =
            names.entries.filter { (_, login) -> login in logins }.associate { (id, login) -> login to id }

        override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> =
            names.filterKeys { it in memberIds }
    }
    private val recipients = MemberTransferRecipients(directory, logins)

    @Test
    fun thePickerListsEveryMemberWithALoginByLoginAndMarksTheAdministrators() = runTest {
        assertEquals(
            listOf(
                TransferRecipient(ada.toString(), "Ada", true),
                TransferRecipient(bob.toString(), "bob", false),
            ),
            recipients.list(ORG_A, 10),
        )
        roles = null
        assertEquals(emptyList(), recipients.list(ORG_A, 10))
    }

    @Test
    fun aRecipientIsNamedByIdOrByLoginAndOnlyAnActiveMemberIsEligible() = runTest {
        assertEquals(bob.toString(), recipients.find(ORG_A, bob.toString()))
        assertEquals(bob.toString(), recipients.find(ORG_A, "bob"))
        assertNull(recipients.find(ORG_A, "nobody"))
        assertTrue(recipients.eligible(ORG_A, ada.toString()))
        assertFalse(recipients.eligible(ORG_A, bob.toString()))
    }

    @Test
    fun membersOfferUnlessTheInstanceDisabledTransfers() = runTest {
        assertTrue(RuntimeMemberTransferPolicy(null).membersMayOffer())
        for ((setting, offers) in listOf(CloudRuntime.TRANSFERS_OFFER to true, "disabled" to false)) {
            val source = object : CloudRuntimeSource {
                override suspend fun current() = CloudRuntime(memberTransfers = setting)
            }
            assertEquals(offers, RuntimeMemberTransferPolicy(source).membersMayOffer())
        }
    }
}
