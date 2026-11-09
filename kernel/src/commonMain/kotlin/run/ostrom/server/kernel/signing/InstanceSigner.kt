// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.signing

/**
 * The instance's signing key: it signs receipts and compiled packages, and clients verify them with the public key
 * the instance publishes. Provisional until ostrom-core publishes its own signer; the contract stays the same.
 */
interface InstanceSigner {
    /** The algorithm, `ed25519`. */
    val algorithm: String

    /** The key id: the first 16 hex characters of the SHA-256 of the public key in DER (SPKI), as the prototype. */
    val keyId: String

    /** The public key in PEM (SPKI), as published to clients. */
    val publicKeyPem: String

    /**
     * Signs bytes (Ed25519, RFC 8032, pure, no pre-hash).
     *
     * @param data The bytes to sign, e.g. the canonical form of a receipt.
     *
     * @return The 64-byte signature.
     */
    suspend fun sign(data: ByteArray): ByteArray

    /**
     * Verifies a signature made by this key.
     *
     * @param data The signed bytes.
     * @param signature The signature.
     *
     * @return True when the signature is valid.
     */
    suspend fun verify(data: ByteArray, signature: ByteArray): Boolean
}
