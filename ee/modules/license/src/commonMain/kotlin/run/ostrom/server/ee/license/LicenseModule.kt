// SPDX-License-Identifier: LicenseRef-Ostrom-Enterprise
package run.ostrom.server.ee.license

import run.ostrom.server.billing.domain.LicenseProvider
import run.ostrom.server.ee.license.domain.LicenseSettings
import run.ostrom.server.ee.license.domain.LicenseVerifier
import run.ostrom.server.ee.license.domain.VerifiedLicenseProvider
import run.ostrom.server.ee.license.infrastructure.LicenseProviderImpl
import run.ostrom.server.ee.license.infrastructure.LicenseVerifierImpl
import run.ostrom.server.ee.license.infrastructure.PinnedLicenseKeys
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsSection
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * The enterprise license module: verifies the license key an administrator of the instance stores in the console
 * (Admin → Settings → license, [LicenseSettings]) offline against the public keys compiled in, on save and at start,
 * and binds billing's [LicenseProvider] (billing falls back to no license without it). It comes after the billing,
 * instance and admin modules (`eeModules()` follows every Apache-2.0 module), so `OSTROM_LICENSE_KEY` is imported
 * before the start verifies the stored key. A key that cannot be used grants nothing, with the reason logged; no key
 * means no license. It serves no route, so it lists no feature in `/api/v1/capabilities` (`license.v1` comes with a
 * route that shows the license).
 */
object LicenseModule : ServerModule {
    override val name: String = "license"

    override val koinModule: Module = module {
        single<LicenseVerifier> { LicenseVerifierImpl(PinnedLicenseKeys.ALL) }
        single<VerifiedLicenseProvider> {
            val settings = get<InstanceSettings>()
            LicenseProviderImpl(
                { settings.read(LicenseSettings.SECTION_NAME)[LicenseSettings.LICENSE_KEY] },
                get(),
                get(),
                get(),
                get(),
            )
        }
        single<LicenseProvider> { get<VerifiedLicenseProvider>() }
        // The key an administrator sets in the console (the admin module collects every bound section).
        single<SettingsSection>(named(LicenseSettings.SECTION_NAME)) { LicenseSettings.section(get()) }
    }

    override suspend fun initialize(koin: Koin) {
        koin.get<VerifiedLicenseProvider>().load()
    }
}
