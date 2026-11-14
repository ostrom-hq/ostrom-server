// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.infrastructure

import run.ostrom.server.kernel.config.ConfigSource

/**
 * The webhooks' configuration, read once.
 *
 * @property allowPrivateTargets `OSTROM_WEBHOOKS_ALLOW_PRIVATE_TARGETS=true`: loopback and private targets are
 *   allowed (an internal receiver of a self-hosted instance); link-local, unspecified and metadata ones never are.
 */
data class WebhooksConfig(val allowPrivateTargets: Boolean) {
    /** Reading the configuration. */
    companion object {
        /**
         * Reads it.
         *
         * @param source The configuration.
         *
         * @return The configuration.
         */
        fun from(source: ConfigSource): WebhooksConfig =
            WebhooksConfig(allowPrivateTargets = source["OSTROM_WEBHOOKS_ALLOW_PRIVATE_TARGETS"] == "true")
    }
}
