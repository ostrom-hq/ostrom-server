package dev.ostrom.server.orgs.presentation

import dev.ostrom.server.kernel.config.ConfigSource

/**
 * Where invitation links lead.
 *
 * @property consoleUrl The console's URL (`OSTROM_CONSOLE_URL`, the same key as the identity
 *   module's), without a trailing slash.
 */
data class OrgsConfig(val consoleUrl: String) {
    /**
     * The console page that accepts an invitation. The token is in the fragment, which browsers
     * never send to a server nor put in a `Referer`: the console reads it and posts it to
     * `/api/v1/invitations/accept`.
     *
     * @param token The invitation's token.
     * @return The link.
     */
    fun invitationUrl(token: String): String = "$consoleUrl/invite#token=$token"

    companion object {
        /**
         * Reads the configuration.
         *
         * @param source The configuration source.
         * @return The configuration.
         */
        fun from(source: ConfigSource): OrgsConfig =
            OrgsConfig(
                source["OSTROM_CONSOLE_URL"]?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
                    ?: "http://localhost:5173"
            )
    }
}
