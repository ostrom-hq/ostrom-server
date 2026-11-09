// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.instance.infrastructure

import run.ostrom.server.instance.domain.PreviousSigningKey
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.signing.Ed25519InstanceSigner
import run.ostrom.server.kernel.signing.InstanceSigner

/**
 * [PreviousSigningKey] read from `OSTROM_PREVIOUS_SIGNING_KEY_FILE` (docs/receipt-key-rotation.md): the PEM file of
 * the key the instance signed with before `OSTROM_SIGNING_KEY_FILE`. Only read, never created: a missing file stops
 * the start with a configuration error.
 *
 * @param config The configuration.
 */
class ConfiguredPreviousSigningKey(private val config: ConfigSource) : PreviousSigningKey {
    override suspend fun signer(): InstanceSigner? = config[KEY]?.let { Ed25519InstanceSigner.load(it) }

    private companion object {
        const val KEY = "OSTROM_PREVIOUS_SIGNING_KEY_FILE"
    }
}
