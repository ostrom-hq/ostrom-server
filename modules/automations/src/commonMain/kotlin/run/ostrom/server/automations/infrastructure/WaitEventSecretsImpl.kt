// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.infrastructure

import run.ostrom.server.automations.domain.WaitEventSecrets
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom

/** [WaitEventSecrets] on the platform's cryptography: 256 random bits, and their SHA-256. */
internal class WaitEventSecretsImpl : WaitEventSecrets {
    private val hasher = CryptographyProvider.Default.get(SHA256).hasher()

    override fun mint(): String = PREFIX + CryptographyRandom.nextBytes(BYTES).toHexString()

    override suspend fun hash(secret: String): String = hasher.hash(secret.encodeToByteArray()).toHexString()

    private companion object {
        const val PREFIX = "wev_"
        const val BYTES = 32
    }
}
