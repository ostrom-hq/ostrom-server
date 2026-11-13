// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.ViewTicket
import run.ostrom.server.artifacts.infrastructure.KernelViewerChecks
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.TokenAuthenticator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ViewerChecksTest {
    private val org = Uuid.random()
    private val ticket = ViewTicket(Uuid.random(), 1, "member", "user", "session", 0)
    private val live = object : TokenAuthenticator {
        override suspend fun authenticate(token: String): AuthenticatedPrincipal? = null

        override suspend fun isLive(subjectId: String, credentialId: String): Boolean =
            subjectId == "user" && credentialId == "session"
    }

    private fun members(vararg ids: String) = object : MemberDirectory {
        override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean = orgId == org && memberId in ids
    }

    @Test
    fun aReaderStandsWhileTheirCredentialLivesAndTheyAreStillAMember() = runTest {
        assertTrue(KernelViewerChecks(live, members("member")).stillSignedIn(org, ticket))
        assertFalse(KernelViewerChecks(live, members()).stillSignedIn(org, ticket), "Left the organization")
        assertFalse(KernelViewerChecks(live, members("member")).stillSignedIn(org, ticket.copy(credentialId = "x")))
        assertFalse(KernelViewerChecks(live, null).stillSignedIn(org, ticket), "Without the orgs module, nobody")
    }
}
