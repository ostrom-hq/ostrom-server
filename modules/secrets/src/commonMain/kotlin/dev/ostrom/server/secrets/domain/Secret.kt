package dev.ostrom.server.secrets.domain

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A secret's metadata. Its value never leaves the server: it is decrypted only to be injected into an upstream
 * request (an MCP server, a model provider) and is scrubbed from what comes back.
 *
 * @property id The secret id.
 * @property orgId The org that owns it.
 * @property name A name unique per org and owner (`[A-Za-z0-9_.-]`, 1 to 128 characters).
 * @property ownerMemberId The member a personal secret belongs to, or null for an org secret.
 * @property version Bumped at each rotation, so a consumer can tell a rotated value apart.
 * @property createdAt When it was created.
 * @property updatedBy The member who last wrote its value.
 * @property updatedAt When its value last changed.
 */
data class Secret(
    val id: Uuid,
    val orgId: Uuid,
    val name: String,
    val ownerMemberId: Uuid?,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: Uuid,
)

/**
 * A secret value in memory. [toString] never shows it, so a log or an exception message cannot leak it.
 *
 * @param value The plaintext.
 */
class SecretValue(private val value: String) {
    /** The plaintext, for the one place that injects it upstream. */
    fun reveal(): String = value

    override fun toString(): String = "SecretValue([REDACTED])"

    override fun equals(other: Any?): Boolean = other is SecretValue && other.value == value

    override fun hashCode(): Int = value.hashCode()
}

/** Validation rules of secret names and values. */
object SecretRules {
    /** The longest value accepted, in UTF-8 bytes. */
    const val MAX_VALUE_BYTES: Int = 16 * 1024

    /** The most secrets listed per page. */
    const val MAX_PAGE: Int = 200

    private val namePattern = Regex("[A-Za-z0-9_.-]{1,128}")

    /**
     * Checks a name.
     *
     * @param name The name.
     *
     * @throws InvalidSecretException When it is not 1 to 128 characters of `A-Z a-z 0-9 _ . -`.
     */
    fun requireName(name: String) {
        if (!namePattern.matches(
                name,
            )
        ) {
            throw InvalidSecretException("A secret name is 1 to 128 characters of A-Z a-z 0-9 _ . -.")
        }
    }

    /**
     * Checks a value.
     *
     * @param value The value.
     *
     * @throws InvalidSecretException When it is empty, too long or holds a NUL character.
     */
    fun requireValue(value: String) {
        if (value.isEmpty() || value.encodeToByteArray().size > MAX_VALUE_BYTES || '\u0000' in value) {
            throw InvalidSecretException("A secret value is 1 byte to 16 KiB, without NUL.")
        }
    }
}

/** A malformed secret name or value. */
class InvalidSecretException(message: String) : OstromException(ErrorKind.INVALID_REQUEST, message)

/** No secret with this id in the caller's org. */
class SecretNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Secret not found.")

/** Another secret already has this name for the same owner. */
class SecretNameTakenException : OstromException(ErrorKind.CONFLICT, "A secret with this name already exists.")

/** A stored envelope that does not decrypt with the instance key (wrong key, or a row moved to another org). */
class SecretUnreadableException :
    OstromException(ErrorKind.UNAVAILABLE, "A stored secret cannot be decrypted with this instance's key.")
