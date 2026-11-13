// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import run.ostrom.server.kernel.auth.ServiceKeyScopes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServiceKeyScopesTest {
    @Test
    fun theReservedScopesAreTheAdministratorsAndNeverACiScope() {
        assertEquals(
            setOf("team:read", "cloud:admin", "skills:publish", "skills:pin", "skills:assign"),
            ServiceKeyScopes.RESERVED,
        )
        assertEquals(setOf("sessions:write", "rules:read"), ServiceKeyScopes.CI)
        assertTrue(ServiceKeyScopes.RESERVED.none { it in ServiceKeyScopes.CI })
    }
}
