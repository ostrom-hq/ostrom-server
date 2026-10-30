package dev.ostrom.server.gates.infrastructure

import dev.ostrom.server.gates.domain.services.GateDigester
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256

/** [GateDigester] with SHA-256 (cryptography-kotlin, OpenSSL on native). */
@OptIn(ExperimentalStdlibApi::class)
class Sha256GateDigester : GateDigester {
    private val hasher = CryptographyProvider.Default.get(SHA256).hasher()

    override fun digest(text: String): String = hasher.hashBlocking(text.encodeToByteArray()).toHexString()
}
