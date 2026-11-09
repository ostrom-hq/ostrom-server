// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.signing

/**
 * Verifies a signature with whichever key of the instance made it, by its key id: the current key and every key the
 * instance signed with before (keys are never forgotten). Something signed with a key id the instance never had is
 * not the instance's.
 */
interface InstanceKeyring {
    /**
     * Verifies [signature] of [data] with the key [keyId].
     *
     * @param keyId The key id the signed thing names.
     * @param data The signed bytes.
     * @param signature The signature.
     *
     * @return Whether it verifies, or null when no key of the instance has that id.
     */
    suspend fun verify(keyId: String, data: ByteArray, signature: ByteArray): Boolean?
}

/**
 * [InstanceKeyring] on the current key alone: the default when no module knows the instance's past keys (the instance
 * module binds the full ring).
 *
 * @param signer The instance's key.
 */
class CurrentKeyring(private val signer: InstanceSigner) : InstanceKeyring {
    override suspend fun verify(keyId: String, data: ByteArray, signature: ByteArray): Boolean? =
        if (keyId == signer.keyId) signer.verify(data, signature) else null
}
