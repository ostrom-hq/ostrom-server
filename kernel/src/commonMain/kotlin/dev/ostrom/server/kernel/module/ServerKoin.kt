// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.module

import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.RejectingPrincipalOrgResolver
import dev.ostrom.server.kernel.auth.RejectingServiceKeyAudit
import dev.ostrom.server.kernel.auth.RejectingTokenAuthenticator
import dev.ostrom.server.kernel.auth.ServiceKeyAudit
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Builds the server's Koin: the kernel, then every module, **without overrides** (two modules binding the same type
 * fail the start instead of one silently replacing the other). The only defaults a module may replace are the
 * [TokenAuthenticator], the [PrincipalOrgResolver] and the [ServiceKeyAudit]: when no module binds them (before the
 * identity and orgs modules exist), every token is rejected, no one is a member of any organization and no service
 * key acts.
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
    if (koin.getOrNull<PrincipalOrgResolver>() == null) {
        koin.loadModules(listOf(module { single<PrincipalOrgResolver> { RejectingPrincipalOrgResolver() } }))
    }
    if (koin.getOrNull<ServiceKeyAudit>() == null) {
        koin.loadModules(listOf(module { single<ServiceKeyAudit> { RejectingServiceKeyAudit() } }))
    }
    if (overrides != null) koin.loadModules(listOf(overrides), allowOverride = true)
    return koin
}
