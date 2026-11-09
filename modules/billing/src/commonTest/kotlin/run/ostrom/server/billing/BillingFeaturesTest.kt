// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.billing

import run.ostrom.server.billing.domain.BillingSettings
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

class BillingFeaturesTest {
    private fun koin(stripeEnabled: Boolean) =
        koinApplication { modules(module { single { BillingSettings(stripeEnabled, "https://app.ostrom.run") } }) }.koin

    @Test
    fun billingIsListedWithStripeConfigured() = runTest {
        assertEquals(setOf("billing.v1"), BillingModule.features(koin(stripeEnabled = true)))
    }

    @Test
    fun billingIsNotListedWithoutStripe() = runTest {
        assertEquals(emptySet(), BillingModule.features(koin(stripeEnabled = false)), "Self-hosted: nothing to buy")
    }
}
