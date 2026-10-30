package dev.ostrom.server.kernel.signing

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import platform.posix.EEXIST
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.fsync
import platform.posix.link
import platform.posix.open
import platform.posix.unlink
import platform.posix.write
import kotlin.uuid.Uuid

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
            if (!SystemFileSystem.exists(file)) {
                val signer = generate()
                file.parent?.let { SystemFileSystem.createDirectories(it) }
                val pem = signer.privateKey.encodeToByteArray(EdDSA.PrivateKey.Format.PEM).decodeToString()
                if (createPrivateFile(path, pem)) return signer
                // Another process created the key first: use its key, never a second one.
            }
            val pem = SystemFileSystem.source(file).buffered().use { it.readString() }
            val privateKey = edDsa.privateKeyDecoder(EdDSA.Curve.Ed25519)
                .decodeFromByteArray(EdDSA.PrivateKey.Format.PEM, pem.encodeToByteArray())
            return of(privateKey, privateKey.getPublicKey())
        }

        /**
         * Writes a file readable by its owner only, atomically and without overwriting: the content goes to a
         * temporary file created with mode 0600 (never readable by others, even briefly), flushed to disk, then
         * hard-linked to [path], which fails if [path] exists.
         *
         * @return True when the file was created, false when [path] already existed.
         */
        private fun createPrivateFile(path: String, content: String): Boolean {
            val temporary = "$path.${Uuid.random().toHexString()}.tmp"
            val descriptor = open(temporary, O_WRONLY or O_CREAT or O_EXCL, OWNER_READ_WRITE)
            check(descriptor >= 0) { "Cannot create $temporary (errno $errno)." }
            try {
                val bytes = content.encodeToByteArray()
                bytes.usePinned { pinned ->
                    var offset = 0
                    while (offset < bytes.size) {
                        val written = write(descriptor, pinned.addressOf(offset), (bytes.size - offset).convert())
                        check(written > 0) { "Cannot write $temporary (errno $errno)." }
                        offset += written.toInt()
                    }
                }
                check(fsync(descriptor) == 0) { "Cannot flush $temporary (errno $errno)." }
            } finally {
                close(descriptor)
            }
            val linked = link(temporary, path) == 0
            val linkError = errno
            unlink(temporary)
            check(linked || linkError == EEXIST) { "Cannot create $path (errno $linkError)." }
            return linked
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
