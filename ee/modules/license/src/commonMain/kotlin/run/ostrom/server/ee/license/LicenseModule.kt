// SPDX-License-Identifier: LicenseRef-Ostrom-Enterprise
package run.ostrom.server.ee.license

import run.ostrom.server.billing.domain.LicenseProvider
import run.ostrom.server.ee.license.domain.LicenseVerifier
import run.ostrom.server.ee.license.domain.VerifiedLicenseProvider
import run.ostrom.server.ee.license.infrastructure.LicenseProviderImpl
import run.ostrom.server.ee.license.infrastructure.LicenseVerifierImpl
import run.ostrom.server.ee.license.infrastructure.PinnedLicenseKeys
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.EnvironmentConfigSource
import run.ostrom.server.kernel.module.ServerModule
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The enterprise license module: verifies the license key (`OSTROM_LICENSE_KEY` or `_FILE`) offline against the
 * public keys compiled in, and binds billing's [LicenseProvider] (billing falls back to no license without it). It
 * comes after the billing and instance modules (`eeModules()` follows every Apache-2.0 module). An invalid key
 * fails the start with the reason; no key means no license. It serves no route, so it lists no feature in
 * `/api/v1/capabilities` (`license.v1` comes with a route that shows the license).
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
