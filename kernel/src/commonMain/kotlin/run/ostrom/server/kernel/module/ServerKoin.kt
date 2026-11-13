// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.module

import run.ostrom.server.kernel.audit.DiscardingOrgAuditTrail
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.PhoneUnlockProofs
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.RejectingPhoneUnlockProofs
import run.ostrom.server.kernel.auth.RejectingPrincipalOrgResolver
import run.ostrom.server.kernel.auth.RejectingServiceKeyAudit
import run.ostrom.server.kernel.auth.RejectingTokenAuthenticator
import run.ostrom.server.kernel.auth.ServiceKeyAudit
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.settings.DiscardingInstanceAuditTrail
import run.ostrom.server.kernel.settings.EmptyInstanceSettings
import run.ostrom.server.kernel.settings.IgnoringInstanceAdminGrants
import run.ostrom.server.kernel.settings.InstanceAccounts
import run.ostrom.server.kernel.settings.InstanceAdminGrants
import run.ostrom.server.kernel.settings.InstanceAdministrators
import run.ostrom.server.kernel.settings.InstanceAuditTrail
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.NoInstanceAccounts
import run.ostrom.server.kernel.settings.NoInstanceAdministrators
import run.ostrom.server.kernel.signing.CurrentKeyring
import run.ostrom.server.kernel.signing.InstanceKeyring
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Builds the server's Koin: the kernel, then every module, **without overrides** (two modules binding the same type
 * fail the start instead of one silently replacing the other). The only defaults a module may replace are the
 * [TokenAuthenticator], the [PrincipalOrgResolver], the [OrgAuditTrail], the [ServiceKeyAudit], the
 * [PhoneUnlockProofs], the [InstanceKeyring] and the instance's administration ports ([InstanceSettings],
 * [InstanceAdministrators], [InstanceAdminGrants], [InstanceAccounts], [InstanceAuditTrail]): when no module binds
 * them (before the identity, orgs, instance and admin modules exist, or in a module's own tests), every token is
 * rejected, no one is a member of any organization, the audit trails write nothing, no service key acts, no phone
 * unlock proves a yes, only the current key verifies, no setting is stored and nobody administers the instance.
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
    // What the admin module's bindings are built from first: probing one builds it, and a missing dependency would
    // read as a missing binding.
    loadDefault<InstanceAccounts>(koin) { NoInstanceAccounts() }
    loadDefault<InstanceAuditTrail>(koin) { DiscardingInstanceAuditTrail() }
    loadDefault<InstanceSettings>(koin) { EmptyInstanceSettings() }
    loadDefault<InstanceAdministrators>(koin) { NoInstanceAdministrators() }
    loadDefault<InstanceAdminGrants>(koin) { IgnoringInstanceAdminGrants() }
    if (koin.getOrNull<InstanceKeyring>() == null) {
        koin.loadModules(listOf(module { single<InstanceKeyring> { CurrentKeyring(get()) } }))
    }
    return koin
}

/** Binds [create] for [T] when no module bound it. */
private inline fun <reified T : Any> loadDefault(koin: Koin, crossinline create: () -> T) {
    if (koin.getOrNull<T>() == null) koin.loadModules(listOf(module { single<T> { create() } }))
}
