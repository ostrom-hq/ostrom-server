// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.NoOrganizationCredentialException
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.RejectingServiceKeyAudit
import run.ostrom.server.kernel.auth.ServiceKeyAudit
import run.ostrom.server.kernel.auth.ServiceKeyCall
import run.ostrom.server.kernel.auth.admitServiceKey
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.kernel.module.serverKoin
import kotlinx.coroutines.test.runTest
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
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

    private class ProbeModule(override val koinModule: Module) : ServerModule {
        override val name: String = "identity"
    }

    @Test
    fun theAuditTheIdentityModuleBindsIsKept() {
        val identity = ProbeModule(module { single<ServiceKeyAudit> { audit } })
        val bound = serverKoin(module { single<Clock> { Clock.System } }, listOf(identity)).get<ServiceKeyAudit>()
        assertSame(audit, bound)
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
