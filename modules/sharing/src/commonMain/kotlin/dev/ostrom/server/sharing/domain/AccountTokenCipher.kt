package dev.ostrom.server.sharing.domain

import dev.ostrom.server.secrets.domain.SecretCipher
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Seals an account's tokens under the instance key (the secrets module's cipher), bound to the org and the account:
 * a sealed blob copied to another account or org does not open.
 *
 * @param cipher The instance cipher.
 */
class AccountTokenCipher(private val cipher: SecretCipher) {
    /** Seals. */
    suspend fun seal(credentials: AccountCredentials, orgId: Uuid, accountId: Uuid): String {
        val payload = Payload(
            credentials.accessToken,
            credentials.refreshToken,
            credentials.expiresAt.toEpochMilliseconds(),
        )
        val bytes = Json.encodeToString(Payload.serializer(), payload).encodeToByteArray()
        return cipher.seal(bytes, context(orgId, accountId))
    }

    /** Opens; null when the envelope is not this account's, the key changed, or what it holds is not tokens. */
    suspend fun open(envelope: String, orgId: Uuid, accountId: Uuid): AccountCredentials? =
        cipher.open(envelope, context(orgId, accountId))?.let(::decode)?.let {
            AccountCredentials(it.accessToken, it.refreshToken, Instant.fromEpochMilliseconds(it.expiresAtMillis))
        }

    private fun decode(bytes: ByteArray): Payload? = try {
        Json.decodeFromString(Payload.serializer(), bytes.decodeToString())
    } catch (_: SerializationException) {
        null
    }

    @Serializable
    private data class Payload(val accessToken: String, val refreshToken: String, val expiresAtMillis: Long)

    private fun context(orgId: Uuid, accountId: Uuid) = "shared-account:$orgId:$accountId"
}
