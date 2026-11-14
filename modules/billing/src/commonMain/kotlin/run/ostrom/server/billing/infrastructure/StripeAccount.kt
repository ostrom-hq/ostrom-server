// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.billing.infrastructure

import run.ostrom.server.billing.domain.StripeSettings
import run.ostrom.server.kernel.settings.InstanceSettings

/**
 * The instance's Stripe account, as its administrators store it ([StripeSettings]), read at each use.
 *
 * @param settings The instance's settings.
 */
class StripeAccount(private val settings: InstanceSettings) {
    /** @return The account, or null while the key or the webhook secret is unset (a self-hosted instance). */
    suspend fun read(): StripeConfig? {
        val values = settings.read(StripeSettings.SECTION.name)
        val key = values[StripeSettings.SECRET_KEY]
        val webhookSecrets = StripeSettings.webhookSecrets(values[StripeSettings.WEBHOOK_SECRET])
        if (key == null || webhookSecrets.isEmpty()) return null
        val baseUrl = values[StripeSettings.API_BASE] ?: StripeSettings.DEFAULT_API_BASE
        return StripeConfig(key, baseUrl, webhookSecrets = webhookSecrets)
    }
}
