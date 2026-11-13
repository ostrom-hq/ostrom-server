// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.skills.infrastructure

import run.ostrom.server.skills.domain.Sha256
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256

/** [Sha256] with the JDK's cryptography provider. */
internal class JdkSha256 : Sha256 {
    private val hasher = CryptographyProvider.Default.get(SHA256).hasher()

    override suspend fun hex(bytes: ByteArray): String = hasher.hash(bytes).toHexString()
}
