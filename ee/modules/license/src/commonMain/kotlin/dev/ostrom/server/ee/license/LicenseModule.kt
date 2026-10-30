// SPDX-License-Identifier: LicenseRef-Ostrom-Enterprise
package dev.ostrom.server.ee.license

import dev.ostrom.server.billing.domain.LicenseProvider
import dev.ostrom.server.ee.license.domain.LicenseVerifier
import dev.ostrom.server.ee.license.domain.VerifiedLicenseProvider
import dev.ostrom.server.ee.license.infrastructure.LicenseProviderImpl
import dev.ostrom.server.ee.license.infrastructure.LicenseVerifierImpl
import dev.ostrom.server.ee.license.infrastructure.PinnedLicenseKeys
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.EnvironmentConfigSource
import dev.ostrom.server.kernel.module.ServerModule
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The enterprise license module: verifies the license key (`OSTROM_LICENSE_KEY` or `_FILE`) offline against the
 * public keys compiled in, and binds billing's [LicenseProvider] (billing falls back to no license without it). It
 * comes after the billing and instance modules (`eeModules()` follows every AGPL module). An invalid key fails the
 * start with the reason; no key means no license.
 */
object LicenseModule : ServerModule {
    override val name: String = "license"

    override val koinModule: Module = module {
        single { LicenseConfig.load(getOrNull<ConfigSource>() ?: EnvironmentConfigSource()) }
        single<LicenseVerifier> { LicenseVerifierImpl(PinnedLicenseKeys.ALL) }
        single<VerifiedLicenseProvider> {
            LicenseProviderImpl(get<LicenseConfig>().licenseKey, get(), get(), get(), get())
        }
        single<LicenseProvider> { get<VerifiedLicenseProvider>() }
    }

    override suspend fun initialize(koin: Koin) {
        koin.get<VerifiedLicenseProvider>().load()
    }
}
