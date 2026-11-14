// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.presentation

import run.ostrom.server.identity.domain.trimmedOrNull
import run.ostrom.server.kernel.config.ConfigSource

/**
 * Where a developer's machine or an end-to-end test writes its sign-in e-mails, read once at start. The sign-in
 * providers, who may sign up and the mail provider are an administrator's, in the console
 * ([run.ostrom.server.identity.infrastructure.SignInSettings],
 * [run.ostrom.server.identity.infrastructure.MAIL_SETTINGS]).
 *
 * @property mailDirectory A directory sign-in e-mails are written to instead of sent (`OSTROM_MAIL_DIR`); ignored once
 *   Resend is set in the console.
 */
data class SignInConfig(val mailDirectory: String?) {
    /** Reads the configuration from the environment. */
    companion object {
        /**
         * Reads the configuration.
         *
         * @param source The configuration source.
         *
         * @return The configuration.
         */
        fun from(source: ConfigSource): SignInConfig =
            SignInConfig(mailDirectory = trimmedOrNull(source["OSTROM_MAIL_DIR"], Int.MAX_VALUE))
    }
}
