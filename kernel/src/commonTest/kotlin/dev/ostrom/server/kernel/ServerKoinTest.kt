package dev.ostrom.server.kernel

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.RejectingTokenAuthenticator
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.kernel.module.serverKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.time.Clock

class ServerKoinTest {
    private class FakeAuthenticator : TokenAuthenticator {
        override suspend fun authenticate(token: String): AuthenticatedPrincipal? = null
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
}
