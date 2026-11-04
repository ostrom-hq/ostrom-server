// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.module

import dev.ostrom.server.kernel.audit.DiscardingOrgAuditTrail
import dev.ostrom.server.kernel.audit.OrgAuditTrail
import dev.ostrom.server.kernel.auth.PhoneUnlockProofs
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.RejectingPhoneUnlockProofs
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
 * [TokenAuthenticator], the [PrincipalOrgResolver], the [OrgAuditTrail], the [ServiceKeyAudit] and the
 * [PhoneUnlockProofs]: when no module binds them (before the identity and orgs modules exist, or in a module's own
 * tests), every token is rejected, no one is a member of any organization, the audit trail writes nothing, no service
 * key acts and no phone unlock proves a yes.
 *
 * @param kernel The kernel definitions ([kernelModule]).
 * @param modules The functional modules.
 * @param overrides Test-only replacements (fakes), loaded before anything is built, with overrides allowed.
 *
 * @return The Koin instance; close it when the server stops.
 */
fun serverKoin(kernel: Module, modules: List<ServerModule>, overrides: Module? = null): Koin {
    val koin = koinApplication {
        allowOverride(false)
        modules(listOf(kernel) + modules.map { it.koinModule })
    }.koin
    // Overrides first: the checks below build what they find, and a real binding built and then overridden is never
    // dropped when Koin closes, so the next test of the same module would reuse it (and its closed pool).
    if (overrides != null) koin.loadModules(listOf(overrides), allowOverride = true)
    if (koin.getOrNull<TokenAuthenticator>() == null) {
        koin.loadModules(listOf(module { single<TokenAuthenticator> { RejectingTokenAuthenticator() } }))
    }
    if (koin.getOrNull<PrincipalOrgResolver>() == null) {
        koin.loadModules(listOf(module { single<PrincipalOrgResolver> { RejectingPrincipalOrgResolver() } }))
    }
    if (koin.getOrNull<OrgAuditTrail>() == null) {
        koin.loadModules(listOf(module { single<OrgAuditTrail> { DiscardingOrgAuditTrail() } }))
    }
    if (koin.getOrNull<ServiceKeyAudit>() == null) {
        koin.loadModules(listOf(module { single<ServiceKeyAudit> { RejectingServiceKeyAudit() } }))
    }
    if (koin.getOrNull<PhoneUnlockProofs>() == null) {
        koin.loadModules(listOf(module { single<PhoneUnlockProofs> { RejectingPhoneUnlockProofs() } }))
    }
    return koin
}
