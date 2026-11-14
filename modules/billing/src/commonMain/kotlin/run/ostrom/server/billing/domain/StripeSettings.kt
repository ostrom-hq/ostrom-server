// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.billing.domain

import run.ostrom.server.kernel.settings.SettingsField
import run.ostrom.server.kernel.settings.SettingsSection

/**
 * The instance's Stripe account (Admin → Settings → stripe): the secret key and the webhook endpoint's signing
 * secrets, sealed, and Stripe's API (stripe-mock in tests). Billing is on (Ostrom Cloud) once the key and a webhook
 * secret are both stored; without them the instance is self-hosted and its limits come from the license. Read at each
 * use, so that a key an administrator saves applies without a restart. `OSTROM_STRIPE_SECRET_KEY`,
 * `OSTROM_STRIPE_WEBHOOK_SECRET` (or their `_FILE`) and `OSTROM_STRIPE_API_BASE` are imported once.
 */
object StripeSettings {
    /** The secret (or restricted) key, `sk_test_…`, `sk_live_…` or `rk_…`: sealed, never returned. */
    const val SECRET_KEY: String = "secretKey"

    /** The endpoint's `whsec_…` signing secret; two comma-separated during a rotation. Sealed, never returned. */
    const val WEBHOOK_SECRET: String = "webhookSecret"

    /** Stripe's API, [DEFAULT_API_BASE] when unset. */
    const val API_BASE: String = "apiBase"

    /** Stripe's own API. */
    const val DEFAULT_API_BASE: String = "https://api.stripe.com"

    /** The section the admin module serves and stores. */
    val SECTION: SettingsSection = SettingsSection(
        "stripe",
        listOf(
            SettingsField(
                SECRET_KEY,
                secret = true,
                required = true,
                legacyVariable = "OSTROM_STRIPE_SECRET_KEY",
                check = ::checkKey,
            ),
            SettingsField(
                WEBHOOK_SECRET,
                secret = true,
                required = true,
                legacyVariable = "OSTROM_STRIPE_WEBHOOK_SECRET",
                check = ::checkWebhookSecret,
            ),
            SettingsField(API_BASE, legacyVariable = "OSTROM_STRIPE_API_BASE", check = ::checkApiBase),
        ),
    )

    /**
     * The webhook secrets a stored value holds.
     *
     * @param value The stored value, comma-separated.
     *
     * @return Each secret, trimmed; none for an unset value.
     */
    fun webhookSecrets(value: String?): List<String> =
        value.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)

    private fun checkKey(value: String): String? =
        if (KEY.matches(value)) null else "A Stripe secret key starts with sk_ or rk_."

    private fun checkWebhookSecret(value: String): String? {
        val secrets = webhookSecrets(value)
        val valid = secrets.size in 1..MAX_WEBHOOK_SECRETS && secrets.all { it.startsWith("whsec_") }
        return if (valid) null else "One or two comma-separated webhook signing secrets, each starting with whsec_."
    }

    private fun checkApiBase(value: String): String? =
        if (HTTP_URL.matches(value)) null else "Stripe's API is an http or https URL."

    private const val MAX_WEBHOOK_SECRETS = 2
    private val KEY = Regex("(sk|rk)_\\S+")
    private val HTTP_URL = Regex("https?://[^\\s/?#]+(/\\S*)?")
}
