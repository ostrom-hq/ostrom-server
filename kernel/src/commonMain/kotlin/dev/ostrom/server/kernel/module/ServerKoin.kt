package dev.ostrom.server.kernel.module

import dev.ostrom.server.kernel.auth.RejectingTokenAuthenticator
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Builds the server's Koin: the kernel, then every module, **without overrides** (two modules binding the same type
 * fail the start instead of one silently replacing the other). The only default a module may provide is the
 * [TokenAuthenticator]: when no module binds one (before the identity module exists), every token is rejected.
 *
 * @param kernel The kernel definitions ([kernelModule]).
 * @param modules The functional modules.
 * @param overrides Test-only replacements (fakes), loaded last with overrides allowed.
 *
 * @return The Koin instance; close it when the server stops.
 */
fun serverKoin(kernel: Module, modules: List<ServerModule>, overrides: Module? = null): Koin {
    val koin = koinApplication {
        allowOverride(false)
        modules(listOf(kernel) + modules.map { it.koinModule })
    }.koin
    if (koin.getOrNull<TokenAuthenticator>() == null) {
        koin.loadModules(listOf(module { single<TokenAuthenticator> { RejectingTokenAuthenticator() } }))
    }
    if (overrides != null) koin.loadModules(listOf(overrides), allowOverride = true)
    return koin
}
