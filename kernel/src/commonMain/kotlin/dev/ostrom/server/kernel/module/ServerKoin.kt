// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.module

import dev.ostrom.server.kernel.audit.DiscardingOrgAuditTrail
import dev.ostrom.server.kernel.audit.OrgAuditTrail
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.RejectingPrincipalOrgResolver
import dev.ostrom.server.kernel.auth.RejectingTokenAuthenticator
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.kernel.signing.CurrentKeyring
import dev.ostrom.server.kernel.signing.InstanceKeyring
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Builds the server's Koin: the kernel, then every module, **without overrides** (two modules binding the same type
 * fail the start instead of one silently replacing the other). The only defaults a module may replace are the
 * [TokenAuthenticator], the [PrincipalOrgResolver], the [OrgAuditTrail] and the [InstanceKeyring]: when no module binds
 * them (before the identity, orgs and instance modules exist, or in a module's own tests), every token is rejected, no
 * one is a member of any organization, the audit trail writes nothing and only the current key verifies.
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
    if (koin.getOrNull<OrgAuditTrail>() == null) {
        koin.loadModules(listOf(module { single<OrgAuditTrail> { DiscardingOrgAuditTrail() } }))
    }
    if (koin.getOrNull<InstanceKeyring>() == null) {
        koin.loadModules(listOf(module { single<InstanceKeyring> { CurrentKeyring(get()) } }))
    }
    if (overrides != null) koin.loadModules(listOf(overrides), allowOverride = true)
    return koin
}
