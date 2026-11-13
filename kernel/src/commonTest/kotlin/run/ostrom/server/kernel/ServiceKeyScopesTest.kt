// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel

import run.ostrom.server.kernel.auth.ServiceKeyScopes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServiceKeyScopesTest {
    @Test
    fun theReservedScopesAreTheAdministratorsAndNeverACiScope() {
        assertEquals(setOf("team:read", "cloud:admin"), ServiceKeyScopes.RESERVED)
        assertEquals(setOf("sessions:write", "rules:read"), ServiceKeyScopes.CI)
        assertTrue(ServiceKeyScopes.RESERVED.none { it in ServiceKeyScopes.CI })
    }
}
