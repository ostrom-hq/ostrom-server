// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.artifacts.infrastructure

import dev.ostrom.server.artifacts.domain.ContentDigest
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256

/** [ContentDigest] with the platform's cryptography provider (OpenSSL 3 on native). */
internal class Sha256ContentDigest : ContentDigest {
    private val hasher = CryptographyProvider.Default.get(SHA256).hasher()

    override suspend fun sha256(bytes: ByteArray): String = hasher.hash(bytes).toHexString()
}
