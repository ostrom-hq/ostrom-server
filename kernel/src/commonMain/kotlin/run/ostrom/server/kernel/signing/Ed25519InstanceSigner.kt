// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.signing

import run.ostrom.server.kernel.config.InvalidConfigException
import run.ostrom.server.kernel.platform.createPrivateFile
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

    // A signature of another length is refused before the provider sees it: the JDK throws on it instead of saying no.
    override suspend fun verify(data: ByteArray, signature: ByteArray): Boolean =
        signature.size == SIGNATURE_LENGTH && publicKey.signatureVerifier().tryVerifySignature(data, signature)

    /** Creating or loading the instance key. */
    companion object {
        /** The key id is the first 16 hex characters of the SHA-256 of the public key (SPKI DER), as the prototype. */
        private const val KEY_ID_LENGTH = 16

        /** An Ed25519 signature is 64 bytes (RFC 8032). */
        private const val SIGNATURE_LENGTH = 64

        private val edDsa get() = CryptographyProvider.Default.get(EdDSA)

        /**
         * Verifies an Ed25519 signature with a public key the instance published (PEM, SPKI): a past key of the
         * instance, kept to verify what it signed.
         *
         * @param publicKeyPem The public key, as [publicKeyPem] writes it.
         * @param data The signed bytes.
         * @param signature The signature.
         *
         * @return Whether it verifies.
         */
        suspend fun verifyWith(publicKeyPem: String, data: ByteArray, signature: ByteArray): Boolean =
            signature.size == SIGNATURE_LENGTH && edDsa.publicKeyDecoder(EdDSA.Curve.Ed25519)
                .decodeFromByteArray(EdDSA.PublicKey.Format.PEM, publicKeyPem.encodeToByteArray())
                .signatureVerifier().tryVerifySignature(data, signature)

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
         *
         * @throws InvalidConfigException When the file is not an Ed25519 private key.
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
            return read(file)
        }

        /**
         * Reads an existing key from a PEM file, never creating one: the previous key of a receipt key rotation
         * (docs/receipt-key-rotation.md), which must be the key the instance signed with before.
         *
         * @param path The PEM file.
         *
         * @return The signer.
         *
         * @throws InvalidConfigException When the file does not exist, or is not an Ed25519 private key.
         */
        suspend fun load(path: String): Ed25519InstanceSigner {
            val file = Path(path)
            if (!SystemFileSystem.exists(file)) throw InvalidConfigException("Signing key file $path does not exist.")
            return read(file)
        }

        private suspend fun read(file: Path): Ed25519InstanceSigner {
            val privateKey = decode(file)
            return of(privateKey, privateKey.getPublicKey())
        }

        // Whatever the library or the file system says, the start stops on a configuration error that names the file.
        private fun decode(file: Path): EdDSA.PrivateKey = try {
            val pem = SystemFileSystem.source(file).buffered().use { it.readString() }
            edDsa.privateKeyDecoder(EdDSA.Curve.Ed25519)
                .decodeFromByteArrayBlocking(EdDSA.PrivateKey.Format.PEM, pem.encodeToByteArray())
            // not coroutine-reachable: nothing in this block suspends, no cancellation passes through it.
        } catch (error: Exception) {
            throw InvalidConfigException("Signing key file $file is not an Ed25519 private key in PKCS#8 PEM.", error)
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
