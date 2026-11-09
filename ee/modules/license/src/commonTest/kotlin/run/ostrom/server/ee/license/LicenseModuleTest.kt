// SPDX-License-Identifier: LicenseRef-Ostrom-Enterprise
package run.ostrom.server.ee.license

import run.ostrom.server.billing.domain.LicenseProvider
import run.ostrom.server.ee.license.domain.ExpiredLicenseException
import run.ostrom.server.ee.license.domain.LicenseVerifier
import run.ostrom.server.ee.license.domain.UnknownLicenseKeyException
import run.ostrom.server.ee.license.domain.VerifiedLicenseProvider
import run.ostrom.server.ee.license.infrastructure.LicenseVerifierImpl
import run.ostrom.server.instance.domain.GetOrCreateInstanceUseCase
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.kernel.logging.Logger
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

    /**
     * Runs [block] with the module loaded, then closes Koin: `LicenseModule.koinModule` is one object shared by every
     * Koin application, and its singletons stay cached in it until the application that created them is closed.
     */
    private suspend fun withKoin(
        config: Map<String, String>?,
        clock: Clock = MutableClock(),
        trustTestKey: Boolean = true,
        block: suspend (Koin) -> Unit,
    ) {
        val application = koinApplication {
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
        }
        try {
            block(application.koin)
        } finally {
            application.close()
        }
    }

    @Test
    fun theModuleIsNamedLicenseAndHasNoTables() {
        assertEquals("license", LicenseModule.name)
        assertEquals(emptyList(), LicenseModule.migrations)
    }

    @Test
    fun withoutAKeyTheStartSucceedsWithoutALicense() = runTest {
        withKoin(emptyMap()) { koin ->
            LicenseModule.initialize(koin)
            assertNull(koin.get<LicenseProvider>().current())
            assertSame(koin.get<VerifiedLicenseProvider>(), koin.get<LicenseProvider>(), "billing reads this one")
        }
    }

    @Test
    fun withoutAConfigSourceTheEnvironmentIsRead() = runTest {
        // Tests never set OSTROM_LICENSE_KEY: the environment has no license.
        withKoin(null) { koin ->
            LicenseModule.initialize(koin)
            assertNull(koin.get<LicenseConfig>().licenseKey)
        }
    }

    @Test
    fun aValidKeyIsVerifiedAtStartAndBillingReadsIt() = runTest {
        withKoin(mapOf("OSTROM_LICENSE_KEY" to SIGN_PY_FIXTURE)) { koin ->
            LicenseModule.initialize(koin)
            assertTrue(recorder.lines.single().startsWith("INFO license verified {license_id=lic_fixture_0001"))
            assertEquals(setOf("sso", "policies", "retention"), koin.get<LicenseProvider>().current()?.features)
        }
    }

    @Test
    fun theDefaultVerifierTrustsOnlyThePinnedKeys() = runTest {
        withKoin(mapOf("OSTROM_LICENSE_KEY" to SIGN_PY_FIXTURE), trustTestKey = false) { koin ->
            assertFailsWith<UnknownLicenseKeyException> { LicenseModule.initialize(koin) }
        }
    }

    @Test
    fun anUnusableKeyFailsTheStart() = runTest {
        val late = MutableClock(Instant.parse("2028-01-01T00:00:00Z"))
        withKoin(mapOf("OSTROM_LICENSE_KEY" to SIGN_PY_FIXTURE), late) { koin ->
            assertFailsWith<ExpiredLicenseException> { LicenseModule.initialize(koin) }
        }
    }
}
