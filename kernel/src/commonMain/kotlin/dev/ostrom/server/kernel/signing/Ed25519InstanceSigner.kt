// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.signing

import dev.ostrom.server.kernel.platform.createPrivateFile
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/**
 * [InstanceSigner] on OpenSSL 3 (statically linked by cryptography-kotlin), with the key in a PEM file (PKCS#8),
 * the format Node writes, so a prototype key can be reused. The file is created once, mode 0600, atomically; two
 * processes starting together end up with the same key.
 */
class Ed25519InstanceSigner private constructor(
    private val privateKey: EdDSA.PrivateKey,
    private val publicKey: EdDSA.PublicKey,
    override val keyId: String,
    override val publicKeyPem: String,
) : InstanceSigner {
    override val algorithm: String = "ed25519"

    override suspend fun sign(data: ByteArray): ByteArray = privateKey.signatureGenerator().generateSignature(data)

    override suspend fun verify(data: ByteArray, signature: ByteArray): Boolean =
        publicKey.signatureVerifier().tryVerifySignature(data, signature)

    /** Creating or loading the instance key. */
    companion object {
        /** The key id is the first 16 hex characters of the SHA-256 of the public key (SPKI DER), as the prototype. */
        private const val KEY_ID_LENGTH = 16

        private val edDsa get() = CryptographyProvider.Default.get(EdDSA)

        /**
         * Generates a new key, in memory only (tests).
         *
         * @return The signer.
         */
        suspend fun generate(): Ed25519InstanceSigner {
            val pair = edDsa.keyPairGenerator(EdDSA.Curve.Ed25519).generateKey()
            return of(pair.privateKey, pair.publicKey)
        }

        /**
         * Reads the key from a PEM file, or creates the file with a new key (mode 0600) on first start. An existing
         * file is only read, never written: it may be a read-only secret shared by every replica.
         *
         * @param path The PEM file.
         *
         * @return The signer.
         */
        suspend fun loadOrCreate(path: String): Ed25519InstanceSigner = loadOrCreate(path, ::createPrivateFile)

        /**
         * [loadOrCreate] with the file creation replaced, to test two processes creating the key together.
         *
         * @param create Writes the file if absent; false when another process created it first.
         */
        internal suspend fun loadOrCreate(path: String, create: (String, String) -> Boolean): Ed25519InstanceSigner {
            val file = Path(path)
            if (!SystemFileSystem.exists(file)) {
                val signer = generate()
                file.parent?.let { SystemFileSystem.createDirectories(it) }
                val pem = signer.privateKey.encodeToByteArray(EdDSA.PrivateKey.Format.PEM).decodeToString()
                // False when another process created the key first: use its key, never a second one.
                if (create(path, pem)) return signer
            }
            val pem = SystemFileSystem.source(file).buffered().use { it.readString() }
            val privateKey = edDsa.privateKeyDecoder(EdDSA.Curve.Ed25519)
                .decodeFromByteArray(EdDSA.PrivateKey.Format.PEM, pem.encodeToByteArray())
            return of(privateKey, privateKey.getPublicKey())
        }

        private suspend fun of(privateKey: EdDSA.PrivateKey, publicKey: EdDSA.PublicKey): Ed25519InstanceSigner {
            val der = publicKey.encodeToByteArray(EdDSA.PublicKey.Format.DER)
            val digest = CryptographyProvider.Default.get(SHA256).hasher().hash(der)
            return Ed25519InstanceSigner(
                privateKey = privateKey,
                publicKey = publicKey,
                keyId = digest.toHexString().take(KEY_ID_LENGTH),
                publicKeyPem = publicKey.encodeToByteArray(EdDSA.PublicKey.Format.PEM).decodeToString(),
            )
        }
    }
}
