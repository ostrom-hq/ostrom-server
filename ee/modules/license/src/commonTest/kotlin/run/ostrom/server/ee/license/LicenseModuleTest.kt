// SPDX-License-Identifier: LicenseRef-Ostrom-Enterprise
package run.ostrom.server.ee.license

import run.ostrom.server.billing.domain.LicenseProvider
import run.ostrom.server.ee.license.domain.LicenseVerifier
import run.ostrom.server.ee.license.domain.VerifiedLicenseProvider
import run.ostrom.server.ee.license.infrastructure.LicenseVerifierImpl
import run.ostrom.server.instance.domain.GetOrCreateInstanceUseCase
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsSection
import kotlinx.coroutines.test.runTest
import org.koin.core.Koin
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** The module's wiring, without a database: the kernel, instance and admin bindings are replaced by fakes. */
class LicenseModuleTest {
    private val recorder = RecordingLogger()

    /**
     * Runs [block] with the module loaded, then closes Koin: `LicenseModule.koinModule` is one object shared by every
     * Koin application, and its singletons stay cached in it until the application that created them is closed.
     */
    private suspend fun withKoin(
        stored: StoredLicense,
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
                    single<InstanceSettings> { stored }
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
    fun withoutAStoredKeyTheStartSucceedsWithoutALicense() = runTest {
        val stored = StoredLicense()
        withKoin(stored) { koin ->
            LicenseModule.initialize(koin)
            assertNull(koin.get<LicenseProvider>().current())
            assertSame(koin.get<VerifiedLicenseProvider>(), koin.get<LicenseProvider>(), "billing reads this one")
            assertEquals("license", stored.sections.first())
        }
    }

    @Test
    fun aValidStoredKeyIsVerifiedAtStartAndBillingReadsIt() = runTest {
        withKoin(StoredLicense(SIGN_PY_FIXTURE)) { koin ->
            LicenseModule.initialize(koin)
            assertTrue(recorder.lines.single().startsWith("INFO license verified {license_id=lic_fixture_0001"))
            assertEquals(setOf("sso", "policies", "retention"), koin.get<LicenseProvider>().current()?.features)
        }
    }

    @Test
    fun theDefaultVerifierTrustsOnlyThePinnedKeys() = runTest {
        withKoin(StoredLicense(SIGN_PY_FIXTURE), trustTestKey = false) { koin ->
            LicenseModule.initialize(koin)
            assertTrue(recorder.lines.single().startsWith("ERROR license refused {reason="), recorder.lines.single())
            assertNull(koin.get<LicenseProvider>().current())
        }
    }

    @Test
    fun anUnusableStoredKeyStartsWithoutALicenseAndLogsWhy() = runTest {
        val late = MutableClock(Instant.parse("2028-01-01T00:00:00Z"))
        withKoin(StoredLicense(SIGN_PY_FIXTURE), late) { koin ->
            LicenseModule.initialize(koin)
            assertTrue(recorder.lines.single().startsWith("ERROR license refused {reason="), recorder.lines.single())
            assertNull(koin.get<LicenseProvider>().current())
        }
    }

    @Test
    fun theConsoleVerifiesAKeyBeforeStoringIt() = runTest {
        withKoin(StoredLicense()) { koin ->
            val section = koin.get<SettingsSection>(named("license"))
            val field = section.fields.single()
            assertEquals("licenseKey", field.name)
            assertTrue(field.secret)
            assertEquals("OSTROM_LICENSE_KEY", field.legacyVariable)
            assertNull(field.refusal(SIGN_PY_FIXTURE))
            assertEquals(
                "The license key is malformed: it does not start with ostrom-license.v1.",
                field.refusal("not-a-license"),
            )
        }
    }
}
