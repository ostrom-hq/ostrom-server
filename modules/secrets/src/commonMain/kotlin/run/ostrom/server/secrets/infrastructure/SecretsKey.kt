// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.secrets.infrastructure

import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.InvalidConfigException
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.io.encoding.Base64

/**
 * The instance's secrets key: `OSTROM_SECRETS_KEY` (base64 of 32 bytes, from a Kubernetes Secret for example), or
 * the file `OSTROM_SECRETS_KEY_FILE` (default `data/secrets.key`), created with a new random key and mode 0600 at the
 * first start. Losing it loses every stored secret: keep the file on a persistent volume, or set the variable.
 */
object SecretsKey {
    /** The variable holding the key itself. */
    const val KEY_VARIABLE: String = "OSTROM_SECRETS_KEY"

    /** The variable naming the key file. */
    const val FILE_VARIABLE: String = "OSTROM_SECRETS_KEY_FILE"

    /** The key file when [FILE_VARIABLE] is not set. */
    const val DEFAULT_FILE: String = "data/secrets.key"

    /**
     * Reads the key, or creates the file the first time.
     *
     * @param config The configuration.
     * @param defaultFile The key file when [FILE_VARIABLE] is not set (tests use a temporary one).
     *
     * @return The 32-byte key.
     *
     * @throws InvalidConfigException When a configured key is not base64 of 32 bytes.
     */
    fun load(config: ConfigSource, defaultFile: String = DEFAULT_FILE): ByteArray {
        val inline = config[KEY_VARIABLE]
        if (!inline.isNullOrBlank()) return decode(inline, KEY_VARIABLE)
        val file = config[FILE_VARIABLE]
        return loadFile(if (file.isNullOrBlank()) defaultFile else file)
    }

    private fun loadFile(path: String): ByteArray {
        val file = Path(path)
        if (!SystemFileSystem.exists(file)) {
            val key = CryptographyProvider.Default.get(AES.GCM).keyGenerator(AES.Key.Size.B256).generateKeyBlocking()
                .encodeToByteArrayBlocking(AES.Key.Format.RAW)
            file.parent?.let { SystemFileSystem.createDirectories(it) }
            // Created with mode 0600, complete or not at all, never over an existing file: when another replica
            // creates it at the same time, the first file wins and every replica reads it back.
            createOwnerOnlyFile(path, Base64.encode(key) + "\n")
        }
        return decode(SystemFileSystem.source(file).buffered().use { it.readString() }, path)
    }

    /**
     * Decodes a base64 key.
     *
     * @param text The base64 text.
     * @param origin Where it came from, for the error message.
     *
     * @return The key.
     *
     * @throws InvalidConfigException When it is not base64 of 32 bytes.
     */
    fun decode(text: String, origin: String): ByteArray {
        val key = try {
            Base64.decode(text.trim())
        } catch (_: IllegalArgumentException) {
            throw InvalidConfigException("$origin is not base64.")
        }
        if (key.size !=
            AesGcmSecretCipher.KEY_BYTES
        ) {
            throw InvalidConfigException("$origin must hold 32 bytes (base64).")
        }
        return key
    }
}
