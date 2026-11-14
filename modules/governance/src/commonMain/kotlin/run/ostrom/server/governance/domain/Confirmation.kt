// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.governance.domain

import run.ostrom.server.kernel.auth.GovernanceWrite
import kotlinx.serialization.json.JsonElement
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A governance write a person's token asked for, held until an owner or an admin of its organization confirms it on
 * the console (contract `GovernanceConfirmation`).
 *
 * @property id The id, `gcf_` and 32 hexadecimal digits.
 * @property orgId The organization.
 * @property write The write, exactly as it was asked.
 * @property scope The governance scope it uses.
 * @property method The method of the request that asked.
 * @property path Its path.
 * @property summary One line saying what it does.
 * @property before What it replaces when it was asked; null for nothing.
 * @property after What it writes; null for nothing.
 * @property requestedBy Who asked.
 * @property credential Which kind of token asked.
 * @property tokenId The token that asked, when the identity module names it.
 * @property tokenName The name of the personal token that asked, as it was when it asked
 *   ([ConfirmationCredential.API_TOKEN]).
 * @property status Where it stands, as stored: a pending one past [expiresAt] is expired ([statusAt]).
 * @property createdAt When it was asked.
 * @property expiresAt When it expires unanswered.
 * @property answeredBy Who confirmed or refused it.
 * @property answeredAt When.
 * @property result What the confirmed write returned.
 * @property error Why a confirmed write was not written.
 * @property settled False while a confirmed write is being made: it is confirmed, its result is not written yet.
 */
data class Confirmation(
    val id: String,
    val orgId: Uuid,
    val write: GovernanceWrite,
    val scope: String,
    val method: String,
    val path: String,
    val summary: String,
    val before: JsonElement?,
    val after: JsonElement?,
    val requestedBy: ConfirmationPerson,
    val credential: ConfirmationCredential,
    val tokenId: String?,
    val status: ConfirmationStatus,
    val createdAt: Instant,
    val expiresAt: Instant,
    val answeredBy: ConfirmationPerson? = null,
    val answeredAt: Instant? = null,
    val result: JsonElement? = null,
    val error: ConfirmationError? = null,
    val tokenName: String? = null,
    val settled: Boolean = true,
) {
    /**
     * Its status at [now]: a pending confirmation past its expiry is expired, even before the server writes it so.
     *
     * @param now The current time.
     *
     * @return The status.
     */
    fun statusAt(now: Instant): ConfirmationStatus =
        if (status == ConfirmationStatus.PENDING && now >= expiresAt) ConfirmationStatus.EXPIRED else status

    /**
     * Its status as it is shown at [now] ([statusAt]): a confirmed write still being made reads pending, so that a
     * reader waits for its result rather than reads a confirmation with none (the CLI says what the write did).
     *
     * @param now The current time.
     *
     * @return The status shown.
     */
    fun shownAt(now: Instant): ConfirmationStatus =
        if (status == ConfirmationStatus.CONFIRMED && !settled) ConfirmationStatus.PENDING else statusAt(now)

    /**
     * Whether it asks for the same write as [other]: the same kind, request and body.
     *
     * @param other Another write.
     * @param otherMethod Its method.
     * @param otherPath Its path.
     *
     * @return True when the same.
     */
    fun asksFor(other: GovernanceWrite, otherMethod: String, otherPath: String): Boolean =
        write == other && method == otherMethod && path == otherPath
}

/**
 * A member who asked for or answered a confirmation.
 *
 * @property userId The person (their user id, the principal's subject).
 * @property memberId Their membership in the organization.
 */
data class ConfirmationPerson(val userId: String, val memberId: Uuid)

/**
 * Why a confirmed write was not written, as the client sees it.
 *
 * @property type The stable key.
 * @property message The message.
 */
data class ConfirmationError(val type: String, val message: String)

/**
 * An answer to a pending confirmation, as it is written.
 *
 * @property status [ConfirmationStatus.CONFIRMED], [ConfirmationStatus.REFUSED] or [ConfirmationStatus.FAILED].
 * @property by Who answers.
 * @property at When; the confirmation must still be pending then.
 * @property error Why it failed, for [ConfirmationStatus.FAILED].
 */
data class ConfirmationOutcome(
    val status: ConfirmationStatus,
    val by: ConfirmationPerson,
    val at: Instant,
    val error: ConfirmationError? = null,
)

/** Where a confirmation stands; [wire] is the contract's value. */
enum class ConfirmationStatus(val wire: String) {
    PENDING("pending"),
    CONFIRMED("confirmed"),
    REFUSED("refused"),
    EXPIRED("expired"),
    FAILED("failed"),
}

/** The kind of token that asked; [wire] is the contract's value. */
enum class ConfirmationCredential(val wire: String) {
    /** The token of `ostrom login --admin`: it holds a governance scope. */
    ADMIN("admin"),

    /** The CLI's token, `ostrom login`. */
    DEVICE("device"),

    /** A personal token made in the console, whatever its scopes: the confirmation names it. */
    API_TOKEN("api_token"),
}

/** The answer of an owner or an admin. */
enum class ConfirmationAnswer {
    CONFIRM,
    REFUSE,
}

/** The steps of a confirmation, each an event of the organization's signed audit log; [action] is its name. */
enum class ConfirmationStep(val action: String) {
    CREATED("governance.confirmation.created"),
    CONFIRMED("governance.confirmation.confirmed"),
    REFUSED("governance.confirmation.refused"),
    EXPIRED("governance.confirmation.expired"),
    FAILED("governance.confirmation.failed"),
}
