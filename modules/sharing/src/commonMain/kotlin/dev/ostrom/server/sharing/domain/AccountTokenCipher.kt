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
        val payload = Payload(credentials.accessToken, credentials.refreshToken, credentials.expiresAt.toEpochMilliseconds())
        return cipher.seal(Json.encodeToString(Payload.serializer(), payload).encodeToByteArray(), context(orgId, accountId))
    }

    /** Opens; null when the envelope is not this account's or the key changed. */
    suspend fun open(envelope: String, orgId: Uuid, accountId: Uuid): AccountCredentials? {
        val bytes = cipher.open(envelope, context(orgId, accountId)) ?: return null
        val payload = try {
            Json.decodeFromString(Payload.serializer(), bytes.decodeToString())
        } catch (_: SerializationException) {
            return null
        }
        return AccountCredentials(payload.accessToken, payload.refreshToken, Instant.fromEpochMilliseconds(payload.expiresAtMillis))
    }

    @Serializable
    private data class Payload(val accessToken: String, val refreshToken: String, val expiresAtMillis: Long)

    private fun context(orgId: Uuid, accountId: Uuid) = "shared-account:$orgId:$accountId"
}
