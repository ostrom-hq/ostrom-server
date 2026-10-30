// SPDX-License-Identifier: LicenseRef-Ostrom-Enterprise
package dev.ostrom.server.ee.license

import dev.ostrom.server.billing.domain.LicenseProvider
import dev.ostrom.server.ee.license.domain.ExpiredLicenseException
import dev.ostrom.server.ee.license.domain.LicenseVerifier
import dev.ostrom.server.ee.license.domain.UnknownLicenseKeyException
import dev.ostrom.server.ee.license.domain.VerifiedLicenseProvider
import dev.ostrom.server.ee.license.infrastructure.LicenseVerifierImpl
import dev.ostrom.server.instance.domain.GetOrCreateInstanceUseCase
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.MapConfigSource
import dev.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.test.runTest
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** The module's wiring, without a database: the kernel and instance bindings are replaced by fakes. */
class LicenseModuleTest {
    private val recorder = RecordingLogger()

    private fun koin(config: Map<String, String>?, clock: Clock = MutableClock(), trustTestKey: Boolean = true): Koin =
        koinApplication {
            allowOverride(true)
            modules(
                module {
                    single<Logger> { recorder }
                    single<Clock> { clock }
                    single<GetOrCreateInstanceUseCase> { FakeInstance() }
                    if (config != null) single<ConfigSource> { MapConfigSource(config) }
                },
                LicenseModule.koinModule,
                module {
                    if (trustTestKey) single<LicenseVerifier> { LicenseVerifierImpl(listOf(SIGN_PY_TEST_PUBLIC_KEY)) }
                },
            )
        }.koin

    @Test
    fun theModuleIsNamedLicenseAndHasNoTables() {
        assertEquals("license", LicenseModule.name)
        assertEquals(emptyList(), LicenseModule.migrations)
    }

    @Test
    fun withoutAKeyTheStartSucceedsWithoutALicense() = runTest {
        val koin = koin(emptyMap())
        LicenseModule.initialize(koin)
        assertNull(koin.get<LicenseProvider>().current())
        assertSame(koin.get<VerifiedLicenseProvider>(), koin.get<LicenseProvider>(), "billing reads the verified one")
    }

    @Test
    fun withoutAConfigSourceTheEnvironmentIsRead() = runTest {
        // Tests never set OSTROM_LICENSE_KEY: the environment has no license.
        val koin = koin(null)
        LicenseModule.initialize(koin)
        assertNull(koin.get<LicenseConfig>().licenseKey)
    }

    @Test
    fun aValidKeyIsVerifiedAtStartAndBillingReadsIt() = runTest {
        val koin = koin(mapOf("OSTROM_LICENSE_KEY" to SIGN_PY_FIXTURE))
        LicenseModule.initialize(koin)
        assertTrue(recorder.lines.single().startsWith("INFO license verified {license_id=lic_fixture_0001"))
        assertEquals(setOf("sso", "policies", "retention"), koin.get<LicenseProvider>().current()?.features)
    }

    @Test
    fun theDefaultVerifierTrustsOnlyThePinnedKeys() = runTest {
        val koin = koin(mapOf("OSTROM_LICENSE_KEY" to SIGN_PY_FIXTURE), trustTestKey = false)
        assertFailsWith<UnknownLicenseKeyException> { LicenseModule.initialize(koin) }
    }

    @Test
    fun anUnusableKeyFailsTheStart() = runTest {
        val late = MutableClock(Instant.parse("2028-01-01T00:00:00Z"))
        val koin = koin(mapOf("OSTROM_LICENSE_KEY" to SIGN_PY_FIXTURE), late)
        assertFailsWith<ExpiredLicenseException> { LicenseModule.initialize(koin) }
    }
}
