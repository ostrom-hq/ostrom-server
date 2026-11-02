// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.NoOrganizationCredentialException
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.RejectingServiceKeyAudit
import dev.ostrom.server.kernel.auth.ServiceKeyAudit
import dev.ostrom.server.kernel.auth.ServiceKeyCall
import dev.ostrom.server.kernel.auth.admitServiceKey
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.kernel.module.serverKoin
import kotlinx.coroutines.test.runTest
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class ServiceKeyCallsTest {
    private val calls = mutableListOf<ServiceKeyCall>()
    private val audit = object : ServiceKeyAudit {
        override suspend fun record(call: ServiceKeyCall) {
            calls += call
        }
    }
    private val org = Uuid.random()

    private fun key(scopes: Set<String>, orgId: String? = org.toString()) =
        AuthenticatedPrincipal("k1", PrincipalKind.SERVICE, scopes, "k1", orgId, namedOrg = Uuid.random().toString())

    @Test
    fun aKeyActsInItsOwnOrganizationAndEachCallIsAudited() = runTest {
        val admitted = key(setOf("rules:read")).admitServiceKey(audit, "rules:read", "listRules", "/api/v1/rules")
        assertEquals(org, admitted, "Its own organization, never the one a header names")
        assertEquals(listOf(ServiceKeyCall("k1", org, "listRules", "/api/v1/rules")), calls)
    }

    @Test
    fun anythingButAServiceKeyIsLeftToTheRoute() = runTest {
        for (kind in listOf(PrincipalKind.USER, PrincipalKind.AGENT, PrincipalKind.DEVICE)) {
            val principal = AuthenticatedPrincipal("u", kind, setOf("rules:read"), orgId = org.toString())
            assertNull(principal.admitServiceKey(audit, "rules:read", "listRules", "/api/v1/rules"))
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun aKeyWithoutTheScopeOrWithoutOrganizationIsRefusedUnaudited() = runTest {
        val missing = assertFailsWith<OstromException> {
            key(setOf("sessions:write")).admitServiceKey(audit, "rules:read", "listRules", "/api/v1/rules")
        }
        assertEquals(ErrorKind.FORBIDDEN, missing.kind)
        assertFailsWith<NoOrganizationCredentialException> {
            key(setOf("rules:read"), orgId = null).admitServiceKey(audit, "rules:read", "listRules", "/r")
        }
        assertFailsWith<NoOrganizationCredentialException> {
            key(setOf("rules:read"), orgId = "nope").admitServiceKey(audit, "rules:read", "listRules", "/r")
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun noKeyActsUntilTheIdentityModuleBindsTheAudit() = runTest {
        val bound = serverKoin(module { single<Clock> { Clock.System } }, emptyList()).get<ServiceKeyAudit>()
        assertIs<RejectingServiceKeyAudit>(bound)
        assertFailsWith<NoOrganizationCredentialException> {
            key(setOf("rules:read")).admitServiceKey(bound, "rules:read", "listRules", "/api/v1/rules")
        }
    }
}
