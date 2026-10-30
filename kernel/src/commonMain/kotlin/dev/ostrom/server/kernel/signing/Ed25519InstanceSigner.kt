package dev.ostrom.server.kernel.signing

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.cinterop.convert
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import platform.posix.chmod

/**
 * [InstanceSigner] on OpenSSL 3 (statically linked by cryptography-kotlin), with the key in a PEM file (PKCS#8),
 * the format Node writes, so a prototype key can be reused.
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

    companion object {
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
         * Reads the key from a PEM file, or creates the file with a new key (mode 0600) on first start.
         *
         * @param path The PEM file.
         *
         * @return The signer.
         */
        suspend fun loadOrCreate(path: String): Ed25519InstanceSigner {
            val file = Path(path)
            if (SystemFileSystem.exists(file)) {
                val pem = SystemFileSystem.source(file).buffered().use { it.readString() }
                val privateKey = edDsa.privateKeyDecoder(EdDSA.Curve.Ed25519)
                    .decodeFromByteArray(EdDSA.PrivateKey.Format.PEM, pem.encodeToByteArray())
                return of(privateKey, privateKey.getPublicKey())
            }
            val signer = generate()
            file.parent?.let { SystemFileSystem.createDirectories(it) }
            val pem = signer.privateKey.encodeToByteArray(EdDSA.PrivateKey.Format.PEM).decodeToString()
            SystemFileSystem.sink(file).buffered().use { it.writeString(pem) }
            chmod(path, OWNER_READ_WRITE.convert())
            return signer
        }

        private suspend fun of(privateKey: EdDSA.PrivateKey, publicKey: EdDSA.PublicKey): Ed25519InstanceSigner {
            val der = publicKey.encodeToByteArray(EdDSA.PublicKey.Format.DER)
            val digest = CryptographyProvider.Default.get(SHA256).hasher().hash(der)
            return Ed25519InstanceSigner(
                privateKey = privateKey,
                publicKey = publicKey,
                keyId = digest.toHexString().take(16),
                publicKeyPem = publicKey.encodeToByteArray(EdDSA.PublicKey.Format.PEM).decodeToString(),
            )
        }

        private const val OWNER_READ_WRITE = 0x180 // 0600
    }
}
