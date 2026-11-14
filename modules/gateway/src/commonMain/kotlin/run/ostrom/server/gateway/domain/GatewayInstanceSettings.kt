// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.domain

import run.ostrom.server.kernel.settings.SettingsField
import run.ostrom.server.kernel.settings.SettingsSection
import run.ostrom.server.kernel.settings.SettingsValues

/**
 * The instance's model providers (Admin → Settings → gateway), as TeamCode keeps its administrator keys in its account
 * pool: provider keys every organization uses (they come before an organization's own key, which each organization
 * keeps setting as today), sealed, and the providers' base URLs (a proxy, the end-to-end bench's fake provider). Read
 * at each model call. The former `OSTROM_GATEWAY_*` variables are imported once.
 */
object GatewayInstanceSettings {
    /** The section the admin module serves and stores. */
    val SECTION: SettingsSection = SettingsSection(
        "gateway",
        listOf(
            key("anthropicApiKey", "OSTROM_GATEWAY_ANTHROPIC_API_KEY"),
            key("openaiApiKey", "OSTROM_GATEWAY_OPENAI_API_KEY"),
            key("openrouterApiKey", "OSTROM_GATEWAY_OPENROUTER_API_KEY"),
            url("anthropicUrl", "OSTROM_GATEWAY_ANTHROPIC_URL"),
            url("openaiUrl", "OSTROM_GATEWAY_OPENAI_URL"),
            url("openrouterUrl", "OSTROM_GATEWAY_OPENROUTER_URL"),
            url("codexUrl", "OSTROM_GATEWAY_CODEX_URL"),
        ),
    )

    /**
     * The providers a stored section describes.
     *
     * @param values The stored section.
     *
     * @return The instance's keys, and the URLs ([ProviderEndpoints]' defaults for those unset), without a trailing
     *   slash.
     */
    fun providers(values: SettingsValues): InstanceProviders {
        val defaults = ProviderEndpoints()
        fun url(field: String, default: String) = (values[field] ?: default).trimEnd('/')
        return InstanceProviders(
            InstanceProviderKeys(values["anthropicApiKey"], values["openaiApiKey"], values["openrouterApiKey"]),
            ProviderEndpoints(
                anthropic = url("anthropicUrl", defaults.anthropic),
                openai = url("openaiUrl", defaults.openai),
                openrouter = url("openrouterUrl", defaults.openrouter),
                codex = url("codexUrl", defaults.codex),
            ),
        )
    }

    private fun key(name: String, variable: String) = SettingsField(name, secret = true, legacyVariable = variable)

    private fun url(name: String, variable: String) = SettingsField(name, legacyVariable = variable, check = ::checkUrl)

    private fun checkUrl(value: String): String? =
        if (HTTP_URL.matches(value)) null else "A provider's base URL is an http or https URL."

    private val HTTP_URL = Regex("https?://[^\\s/?#]+(/\\S*)?")
}
