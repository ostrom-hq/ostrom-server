// SPDX-License-Identifier: LicenseRef-Ostrom-Enterprise
package run.ostrom.server.ee.license

import run.ostrom.server.billing.BillingModule
import run.ostrom.server.billing.domain.GetPlanLimitsUseCase
import run.ostrom.server.ee.license.domain.LicenseVerifier
import run.ostrom.server.ee.license.infrastructure.LicenseVerifierImpl
import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.testing.serverTest
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** The license module with the real instance and billing modules, on a real PostgreSQL. */
class LicensePostgresTest {
    private val modules = listOf(InstanceModule, BillingModule, LicenseModule)

    private fun overrides(config: Map<String, String>) = module {
        single<ConfigSource> { MapConfigSource(config) }
        single<LicenseVerifier> { LicenseVerifierImpl(listOf(SIGN_PY_TEST_PUBLIC_KEY)) }
        single<Clock> { MutableClock() }
    }

    @Test
    fun withoutAKeyASelfHostedInstanceHasNoEnterpriseFeatures() = serverTest(modules, overrides(emptyMap())) { server ->
        val limits = server.koin.get<GetPlanLimitsUseCase>()(Uuid.random())
        assertFalse(limits.sso)
        assertFalse(limits.policies)
        assertEquals(30, limits.receiptRetentionDays)
        assertNull(limits.maxPeople)
    }

    @Test
    fun aLicenseKeyGrantsBillingItsFeaturesAndPeople() =
        serverTest(modules, overrides(mapOf("OSTROM_LICENSE_KEY" to SIGN_PY_FIXTURE))) { server ->
            val limits = server.koin.get<GetPlanLimitsUseCase>()(Uuid.random())
            assertTrue(limits.sso)
            assertTrue(limits.policies)
            assertNull(limits.receiptRetentionDays, "unlimited retention")
            assertEquals(50, limits.maxPeople)
        }
}
