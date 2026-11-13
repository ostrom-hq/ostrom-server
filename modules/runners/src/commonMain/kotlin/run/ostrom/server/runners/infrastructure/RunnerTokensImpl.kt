// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.runners.domain.RunnerTokens
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom

/** [RunnerTokens] with the system's secure random generator and SHA-256 (OpenSSL through cryptography-kotlin). */
class RunnerTokensImpl : RunnerTokens {
    private val hasher = CryptographyProvider.Default.get(SHA256).hasher()

    override fun mint(): String = PREFIX + CryptographyRandom.nextBytes(TOKEN_BYTES).toHexString()

    override fun hash(token: String): String = hasher.hashBlocking(token.encodeToByteArray()).toHexString()

    private companion object {
        const val PREFIX = "ostrom_runner_"
        const val TOKEN_BYTES = 32
    }
}
