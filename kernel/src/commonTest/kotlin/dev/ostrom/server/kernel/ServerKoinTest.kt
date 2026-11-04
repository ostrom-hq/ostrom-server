// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel

import dev.ostrom.server.kernel.audit.DiscardingOrgAuditTrail
import dev.ostrom.server.kernel.audit.OrgAuditRecord
import dev.ostrom.server.kernel.audit.OrgAuditTrail
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.RejectingPrincipalOrgResolver
import dev.ostrom.server.kernel.auth.RejectingTokenAuthenticator
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.kernel.module.serverKoin
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class ServerKoinTest {
    private class FakeAuthenticator : TokenAuthenticator {
        override suspend fun authenticate(token: String): AuthenticatedPrincipal? = null
    }

    private class FakeResolver : PrincipalOrgResolver {
        override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? = null
    }

    private class ProbeModule(override val name: String, override val koinModule: Module) : ServerModule

    private val kernel = module { single<Clock> { Clock.System } }

    @Test
    fun rejectsEveryTokenUntilAModuleBindsAnAuthenticator() {
        assertIs<RejectingTokenAuthenticator>(serverKoin(kernel, emptyList()).get<TokenAuthenticator>())
    }

    @Test
    fun usesTheAuthenticatorOfTheIdentityModule() {
        val identity = ProbeModule("identity", module { single<TokenAuthenticator> { FakeAuthenticator() } })
        assertIs<FakeAuthenticator>(serverKoin(kernel, listOf(identity)).get<TokenAuthenticator>())
    }

    @Test
    fun twoModulesBindingTheSameTypeFailTheStart() {
        val first = ProbeModule("first", module { single<TokenAuthenticator> { FakeAuthenticator() } })
        val second = ProbeModule("second", module { single<TokenAuthenticator> { RejectingTokenAuthenticator() } })
        assertFails { serverKoin(kernel, listOf(first, second)) }
    }

    @Test
    fun aModuleCannotReplaceAKernelBinding() {
        val rogue = ProbeModule("rogue", module { single<Clock> { Clock.System } })
        assertFails { serverKoin(kernel, listOf(rogue)) }
    }

    @Test
    fun resolvesNoMemberUntilAModuleBindsAResolver() = runTest {
        val resolver = serverKoin(kernel, emptyList()).get<PrincipalOrgResolver>()
        assertIs<RejectingPrincipalOrgResolver>(resolver)
        assertNull(resolver.resolve(AuthenticatedPrincipal("user-1", PrincipalKind.USER, emptySet()), Uuid.random()))
        assertNull(RejectingTokenAuthenticator().authenticate("any-token"))
    }

    @Test
    fun usesTheResolverOfTheOrgsModule() {
        val orgs = ProbeModule("orgs", module { single<PrincipalOrgResolver> { FakeResolver() } })
        assertIs<FakeResolver>(serverKoin(kernel, listOf(orgs)).get<PrincipalOrgResolver>())
    }

    @Test
    fun writesNoAuditUntilTheIdentityModuleBindsTheTrail() = runTest {
        val trail = serverKoin(kernel, emptyList()).get<OrgAuditTrail>()
        assertIs<DiscardingOrgAuditTrail>(trail)
        val actor = AuthenticatedPrincipal("user-1", PrincipalKind.USER, emptySet())
        trail.record(OrgAuditRecord(Uuid.random(), actor, "budget.changed", "budget", "org"))
        val bound = OrgAuditTrail { }
        val identity = ProbeModule("identity", module { single<OrgAuditTrail> { bound } })
        assertSame(bound, serverKoin(kernel, listOf(identity)).get<OrgAuditTrail>())
    }

    @Test
    fun testOverridesReplaceARealBinding() {
        val identity = ProbeModule("identity", module { single<TokenAuthenticator> { RejectingTokenAuthenticator() } })
        val overrides = module { single<TokenAuthenticator> { FakeAuthenticator() } }
        assertIs<FakeAuthenticator>(serverKoin(kernel, listOf(identity), overrides).get<TokenAuthenticator>())
    }

    @Test
    fun anOverriddenBindingIsNeverBuilt() {
        var built = 0
        val identity = ProbeModule(
            "identity",
            module { single<TokenAuthenticator> { built++; RejectingTokenAuthenticator() } },
        )
        val overrides = module { single<TokenAuthenticator> { FakeAuthenticator() } }
        val koin = serverKoin(kernel, listOf(identity), overrides)
        assertIs<FakeAuthenticator>(koin.get<TokenAuthenticator>())
        koin.close()
        assertEquals(0, built)
    }

    @Test
    fun aModuleHasNoMigrationOrWorkByDefault() = runTest {
        val module = ProbeModule("probe", module {})
        val koin = serverKoin(kernel, listOf(module))
        assertEquals(emptyList(), module.migrations)
        module.initialize(koin)
        module.start(CoroutineScope(coroutineContext), koin)
    }

    @Test
    fun aModuleHasNoRouteByDefault() = testApplication {
        val module = ProbeModule("probe", module {})
        var routed = false
        routing {
            module.routes(this, serverKoin(kernel, listOf(module)))
            routed = true
        }
        startApplication()
        assertTrue(routed)
    }
}
