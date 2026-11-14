// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.presentation

import run.ostrom.server.identity.domain.trimmedOrNull
import run.ostrom.server.kernel.config.ConfigSource

/**
 * How sign-in e-mails are sent, read once at start. The sign-in providers (GitHub, Google) and who may sign up are an
 * administrator's, in the console ([run.ostrom.server.identity.infrastructure.SignInSettings]).
 *
 * @property resendApiKey Resend's key (`OSTROM_RESEND_API_KEY`).
 * @property mailFrom The sender of sign-in e-mails (`OSTROM_MAIL_FROM`).
 * @property mailDirectory A directory sign-in e-mails are written to instead of sent (`OSTROM_MAIL_DIR`), for a
 *   developer's machine and end-to-end tests; ignored when Resend is configured.
 */
data class SignInConfig(
    val resendApiKey: String?,
    val mailFrom: String,
    val mailDirectory: String?,
) {
    /** Reads the configuration from the environment. */
    companion object {
        /**
         * Reads the configuration.
         *
         * @param source The configuration source.
         *
         * @return The configuration.
         */
        fun from(source: ConfigSource): SignInConfig = SignInConfig(
            resendApiKey = value(source, "OSTROM_RESEND_API_KEY"),
            mailFrom = value(source, "OSTROM_MAIL_FROM") ?: "Ostrom <login@mail.ostrom.run>",
            mailDirectory = value(source, "OSTROM_MAIL_DIR"),
        )

        private fun value(source: ConfigSource, key: String): String? = trimmedOrNull(source[key], Int.MAX_VALUE)
    }
}
