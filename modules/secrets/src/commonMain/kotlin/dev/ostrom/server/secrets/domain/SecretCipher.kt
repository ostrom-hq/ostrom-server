package dev.ostrom.server.secrets.domain

/**
 * Seals values at rest. The [context] is authenticated with the ciphertext (AES-GCM associated data): an envelope
 * copied to another row, org or member does not open.
 */
interface SecretCipher {
    /**
     * Encrypts a value.
     *
     * @param plaintext The value.
     * @param context What the envelope is bound to, such as `secret:<org>:<id>`.
     *
     * @return The envelope, printable.
     */
    suspend fun seal(plaintext: ByteArray, context: String): String

    /**
     * Decrypts an envelope.
     *
     * @param envelope The envelope [seal] produced.
     * @param context The same context as at sealing.
     *
     * @return The value, or null when the envelope is malformed, sealed by another key or bound to another context.
     */
    suspend fun open(envelope: String, context: String): ByteArray?
}

/** The context a stored secret is sealed with. */
fun secretContext(orgId: Any, secretId: Any): String = "secret:$orgId:$secretId"
