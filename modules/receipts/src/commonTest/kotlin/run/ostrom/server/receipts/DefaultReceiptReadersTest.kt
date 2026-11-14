// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.receipts

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.receipts.domain.models.ReceiptReader
import run.ostrom.server.receipts.infrastructure.NoReceiptOwners
import run.ostrom.server.receipts.infrastructure.OrgReceiptReaders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/** Without the gates and sessions modules, a person is known by their organization and role only, and owns nothing. */
class DefaultReceiptReadersTest {
    private val orgs = object : PrincipalOrgResolver {
        override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
            when (principal.subjectId) {
                "owner" -> OrgPrincipal(ORG_A, Uuid.random(), OrgRole.OWNER)
                "member" -> OrgPrincipal(ORG_A, Uuid.random(), OrgRole.MEMBER)
                else -> null
            }
    }

    private fun person(id: String) = AuthenticatedPrincipal(id, PrincipalKind.USER, setOf("user:*"))

    @Test
    fun aPersonIsTheirOrganizationAndRoleAndApprovesNothing() = runTest {
        val readers = OrgReceiptReaders(orgs)
        assertEquals(ReceiptReader(ORG_A, null), readers.resolve(person("owner")), "A role reads no more")
        assertEquals(ReceiptReader(ORG_A, null), readers.resolve(person("member")))
        assertNull(readers.resolve(person("stranger")))
        assertFalse(readers.approves(ORG_A, "rev", "s-1"))
        assertEquals(emptyList(), readers.approvedSessions(ORG_A, "rev"))
        assertNull(NoReceiptOwners().ownerOf(ORG_A, "s-1"))
    }
}
