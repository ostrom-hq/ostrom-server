// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.skills.infrastructure

import run.ostrom.server.skills.domain.Sha256
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256

/** [Sha256] with the platform's cryptography provider (OpenSSL 3 on native). */
internal class OpenSslSha256 : Sha256 {
    private val hasher = CryptographyProvider.Default.get(SHA256).hasher()

    override suspend fun hex(bytes: ByteArray): String = hasher.hash(bytes).toHexString()
}
