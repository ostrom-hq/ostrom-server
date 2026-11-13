// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.billing

import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.InvalidConfigException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/**
 * Billing's configuration (docs/MODULES.md table). Without a Stripe key the instance is self-hosted: limits come
 * from the license key and nothing can be bought. Test and live differ only by the key and the webhook secret.
 *
 * @property stripeSecretKey `OSTROM_STRIPE_SECRET_KEY`, or the content of `OSTROM_STRIPE_SECRET_KEY_FILE`.
 * @property webhookSecrets `OSTROM_STRIPE_WEBHOOK_SECRET` (or `_FILE`): one or two (rotation) comma-separated `whsec_`.
 * @property stripeApiBase `OSTROM_STRIPE_API_BASE`, Stripe by default (stripe-mock in tests).
 * @property consoleUrl `OSTROM_CONSOLE_URL`, `https://app.ostrom.run` by default.
 */
data class BillingConfig(
    val stripeSecretKey: String?,
    val webhookSecrets: List<String>,
    val stripeApiBase: String,
    val consoleUrl: String,
) {
    /** Reading it back. */
    companion object {
        /**
         * Reads the configuration.
         *
         * @param readFile Reads a secret file (the real filesystem by default).
         * @throws InvalidConfigException When a Stripe key is set without a webhook secret, or a file is missing.
         */
        fun load(source: ConfigSource, readFile: (String) -> String = ::readSecretFile): BillingConfig {
            fun secret(name: String): String? =
                source[name] ?: source["${name}_FILE"]?.let { readFile(it).trim().takeIf(String::isNotEmpty) }
            val key = secret("OSTROM_STRIPE_SECRET_KEY")
            val webhookSecrets = secret("OSTROM_STRIPE_WEBHOOK_SECRET").orEmpty()
                .split(',').map(String::trim).filter(String::isNotEmpty)
            if (key != null && webhookSecrets.isEmpty()) {
                throw InvalidConfigException("OSTROM_STRIPE_WEBHOOK_SECRET is required when a Stripe key is set.")
            }
            return BillingConfig(
                stripeSecretKey = key,
                webhookSecrets = webhookSecrets,
                stripeApiBase = source["OSTROM_STRIPE_API_BASE"] ?: "https://api.stripe.com",
                consoleUrl = source["OSTROM_CONSOLE_URL"] ?: "https://app.ostrom.run",
            )
        }
    }
}

private fun readSecretFile(path: String): String {
    val file = Path(path)
    if (!SystemFileSystem.exists(file)) throw InvalidConfigException("Secret file $path does not exist.")
    return SystemFileSystem.source(file).buffered().use { it.readString() }
}
