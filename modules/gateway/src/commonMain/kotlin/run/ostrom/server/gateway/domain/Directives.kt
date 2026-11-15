// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The stages of a delivery (TeamCode's `RECEIVED_BY_CLI`, `EMITTED_TO_HOOK`). */
enum class DeliveryStage {
    /** The CLI received the advice. */
    RECEIVED_BY_CLI,

    /** The CLI's hook gave it to the agent. */
    EMITTED_TO_HOOK,
    ;

    /** The name in the API. */
    val wire: String get() = name.lowercase()

    /** Reading the API's name. */
    companion object {
        /** The stage named [value]; a 400 `invalid_stage` for any other. */
        fun named(value: String): DeliveryStage = entries.firstOrNull { it.wire == value }
            ?: throw InvalidDirectiveRequestException("invalid_stage", "Unknown delivery stage.")
    }
}

/**
 * One scope's advice as the controller stored it, with what the next pass needs (TeamCode's `Stored`).
 *
 * @property directive The advice.
 * @property contextKey The capacity context it was made in: a different one never inherits it.
 * @property recovery The looser candidate being stabilized, if any.
 */
data class StoredDirective(val directive: AgentDirective, val contextKey: String, val recovery: PacingRecovery?)

/**
 * One scope's advice the controller writes.
 *
 * @property memberId The member.
 * @property scopeKey `` for the member-wide advice, else the session's digest.
 * @property directive The advice.
 * @property contextKey Its capacity context.
 * @property recovery The looser candidate being stabilized, if any.
 */
data class DirectiveWrite(
    val memberId: Uuid,
    val scopeKey: String,
    val directive: AgentDirective,
    val contextKey: String,
    val recovery: PacingRecovery?,
)

/**
 * The advice of the capacity controller, shared by every replica (TeamCode's `directives`, `session_directives`,
 * `directive_deliveries` and `directive_receipts`). A session is named by its id, which the repository stores only as
 * its SHA-256 digest, as the shared accounts' affinities do. Every call is confined to one org; times are the
 * database's clock.
 */
interface DirectiveRepository {
    /** The org's stored advice by member and scope key, expired or not. */
    suspend fun stored(orgId: Uuid): Map<Pair<Uuid, String>, StoredDirective>

    /**
     * Writes the pass's advice, then deletes the expired session advice, the deliveries of advice no longer current or
     * older than [RETENTION], and the receipts older than [RETENTION] (at most [PURGE_BATCH] of each).
     */
    suspend fun save(orgId: Uuid, writes: List<DirectiveWrite>)

    /** The member's unexpired advice of [session] (null: member-wide). */
    suspend fun current(orgId: Uuid, memberId: Uuid, session: String?): AgentDirective?

    /**
     * Claims the delivery of the session's advice at [stage] for [tokenId], for [CLAIM_LEASE]: null when the same
     * advice was already delivered (or is being), when none is current, or past [MAX_MEMBER_DELIVERIES] (or
     * [MAX_DELIVERIES] for the org) remembered.
     */
    suspend fun claim(
        orgId: Uuid,
        memberId: Uuid,
        session: String,
        stage: DeliveryStage,
        tokenId: String,
    ): DirectiveDelivery?

    /**
     * Records a receipt; with a session, settles its claim.
     *
     * @throws DirectiveExpiredException When the advice expired or was replaced.
     * @throws ClaimExpiredException When the claim expired, was replaced, or is another token's.
     */
    @Suppress("LongParameterList")
    suspend fun acknowledge(
        orgId: Uuid,
        memberId: Uuid,
        id: String,
        stage: DeliveryStage,
        session: String?,
        claimId: String?,
        tokenId: String,
    )

    /** The bounds (TeamCode's). */
    companion object {
        /** How long an advice applies unless the controller renews it. */
        val EXPIRY: Duration = 3.minutes

        /** How long a delivery claim holds. */
        val CLAIM_LEASE: Duration = 15.seconds

        /** How long deliveries and receipts are kept. */
        val RETENTION: Duration = 7.days

        /** Rows deleted at most per kind and pass. */
        const val PURGE_BATCH: Int = 1_000

        /** Deliveries remembered at most per member. */
        const val MAX_MEMBER_DELIVERIES: Int = 2_000

        /** Deliveries remembered at most per org. */
        const val MAX_DELIVERIES: Int = 20_000
    }
}

/** A malformed directive request: a 400 with its key. */
class InvalidDirectiveRequestException(key: String, message: String) :
    OstromException(ErrorKind.INVALID_REQUEST, message, key)

/** The advice expired or was replaced: a 409 `directive_expired`. */
class DirectiveExpiredException :
    OstromException(ErrorKind.CONFLICT, "This directive has expired or been replaced.", "directive_expired")

/** The claim expired or was replaced: a 409 `claim_expired`. */
class ClaimExpiredException :
    OstromException(ErrorKind.CONFLICT, "This delivery claim expired or was replaced.", "claim_expired")

/** A session id outside `[A-Za-z0-9._-]{1,128}`: a 400 `invalid_session` (TeamCode's check). */
fun requireSessionId(value: String): String {
    if (!SESSION_ID.matches(value)) {
        throw InvalidDirectiveRequestException("invalid_session", "Provide a valid client session identifier.")
    }
    return value
}

private val SESSION_ID = Regex("[A-Za-z0-9._-]{1,128}")
