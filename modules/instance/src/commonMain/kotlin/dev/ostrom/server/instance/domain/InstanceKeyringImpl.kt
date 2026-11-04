// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.instance.domain

import dev.ostrom.server.kernel.signing.Ed25519InstanceSigner
import dev.ostrom.server.kernel.signing.InstanceKeyring
import dev.ostrom.server.kernel.signing.InstanceSigner

/**
 * [InstanceKeyring] on every key the instance has signed with, never forgotten: the current key verifies in memory, a
 * past one by the public key the instance published. The list is read once, and again when a key id is not in it (a
 * replica that started with a newer key); an id still absent then was never the instance's.
 *
 * @param listKeys The instance's keys.
 * @param signer The current key.
 */
class InstanceKeyringImpl(
    private val listKeys: ListSigningKeysUseCase,
    private val signer: InstanceSigner,
) : InstanceKeyring {
    // Replaced whole, never changed in place: a reader sees the old list or the new one.
    private var known: Map<String, String>? = null

    override suspend fun verify(keyId: String, data: ByteArray, signature: ByteArray): Boolean? {
        if (keyId == signer.keyId) return signer.verify(data, signature)
        val publicKeyPem = known?.get(keyId) ?: read()[keyId]
        return publicKeyPem?.let { Ed25519InstanceSigner.verifyWith(it, data, signature) }
    }

    private suspend fun read(): Map<String, String> =
        listKeys().associate { it.keyId to it.publicKeyPem }.also { known = it }
}
