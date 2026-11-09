// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.NoOrganizationCredentialException
import run.ostrom.server.kernel.auth.OrganizationNotFoundException
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.outsideOrganization
import run.ostrom.server.kernel.errors.ErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** A caller outside the organization its request acts in: a person is not found, any other credential is refused. */
class OrgPrincipalTest {
    @Test
    fun aPersonIsNotFoundAndAnyOtherCredentialIsRefused() {
        val person = AuthenticatedPrincipal("user-1", PrincipalKind.USER, emptySet()).outsideOrganization()
        assertIs<OrganizationNotFoundException>(person)
        assertEquals(ErrorKind.NOT_FOUND, person.kind)
        for (kind in PrincipalKind.entries.filter { it != PrincipalKind.USER }) {
            val other = AuthenticatedPrincipal("x", kind, emptySet()).outsideOrganization()
            assertIs<NoOrganizationCredentialException>(other, kind.name)
            assertEquals(ErrorKind.FORBIDDEN, other.kind, kind.name)
        }
    }
}
