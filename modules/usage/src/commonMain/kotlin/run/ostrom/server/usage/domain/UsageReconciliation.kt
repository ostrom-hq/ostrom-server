// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Only request-specific provider evidence can replace an unknown charge (TeamCode's `UsageEvidence`). */
enum class UsageEvidence { PROVIDER_RECORD, PROVIDER_CONFIRMATION }

/**
 * An administrator's correction of an unknown charge (TeamCode's `UsageReconciliationChange`).
 *
 * @property expectedMicros The charge the reviewer saw.
 * @property micros The verified total, USD millionths.
 * @property source The evidence.
 * @property reference A non-secret pointer to the evidence, one line.
 * @property reason Why, one line.
 * @property expectedProviderRequestId The provider request id the reviewer saw, null when none was shown.
 */
data class UsageReconciliationChange(
    val expectedMicros: Long,
    val micros: Long,
    val source: UsageEvidence,
    val reference: String,
    val reason: String,
    val expectedProviderRequestId: String? = null,
)

/**
 * A model call whose final usage never reached the ledger: its hold ran out and stays charged at [heldAt].
 *
 * @property id The call's id, the hold's.
 * @property memberId The member who made it.
 * @property micros The charge kept.
 * @property heldAt When the call first asked.
 * @property leaseExpiredAt When its hold ran out.
 * @property workflowId The workflow its launch played.
 * @property launchId The launch whose agent token made it.
 * @property providerRequestId The provider's request id, once the gateway captures one (TC-USR-3, not yet).
 */
data class PendingUsage(
    val id: Uuid,
    val memberId: Uuid,
    val micros: Long,
    val heldAt: Instant,
    val leaseExpiredAt: Instant,
    val workflowId: String? = null,
    val launchId: String? = null,
    val providerRequestId: String? = null,
)

/**
 * A final correction of an unknown charge, with the attribution the call had when it was held (TeamCode's
 * `ReconciledUsage`).
 *
 * @property id The id of the hold corrected.
 * @property memberId The member the call was charged to.
 * @property workflowId Its workflow then.
 * @property launchId Its launch then.
 * @property providerRequestId The provider request id then.
 * @property heldAt When the call was held: the day the correction applies to.
 * @property previousMicros The charge before.
 * @property micros The verified total.
 * @property source The evidence.
 * @property reference The pointer to it.
 * @property reason Why.
 * @property reconciledBy The administrator.
 * @property reconciledAt When, by the database's clock.
 */
data class ReconciledUsage(
    val id: Uuid,
    val memberId: Uuid,
    val workflowId: String?,
    val launchId: String?,
    val providerRequestId: String?,
    val heldAt: Instant,
    val previousMicros: Long,
    val micros: Long,
    val source: UsageEvidence,
    val reference: String,
    val reason: String,
    val reconciledBy: Uuid,
    val reconciledAt: Instant,
)

/**
 * A page, newest first.
 *
 * @property items At most [UsageReconciliationRules.PAGE_SIZE].
 * @property next The `before` of the following page, null on the last.
 */
data class ReviewPage<T>(val items: List<T>, val next: Uuid?)

/**
 * One hold under review, or its correction: exactly one is set.
 *
 * @property pending While its charge is unknown.
 * @property reconciliation Once corrected.
 */
data class UsageReview(val pending: PendingUsage? = null, val reconciliation: ReconciledUsage? = null)

/** What a correction came to. */
sealed interface ReconcileOutcome {
    /**
     * Corrected now, or the same correction already made ([changed] false: nothing written).
     *
     * @property reconciliation The record.
     * @property changed Whether this call wrote it.
     */
    data class Reconciled(val reconciliation: ReconciledUsage, val changed: Boolean = true) : ReconcileOutcome

    /** No hold of that id under review: none, settled, or still within its lease. */
    data object NotFound : ReconcileOutcome

    /** The charge or the request id is no longer what the reviewer saw. */
    data object Stale : ReconcileOutcome

    /** Another correction already resolved it. */
    data object AlreadyReconciled : ReconcileOutcome
}

/** The unknown usage and its corrections, by org. */
interface UsageReconciliationRepository {
    /**
     * The expired holds no correction resolved, newest first (`held_at`, then id).
     *
     * @param orgId The organization.
     * @param memberId Only this member's, or every member's.
     * @param before The id of the last item of the previous page.
     *
     * @return The page, or null when [before] names no hold of the org.
     */
    suspend fun pending(orgId: Uuid, memberId: Uuid?, before: Uuid?): ReviewPage<PendingUsage>?

    /**
     * The corrections, newest first (`reconciled_at`, then id).
     *
     * @param orgId The organization.
     * @param memberId Only this member's, or every member's.
     * @param before The id of the last item of the previous page.
     *
     * @return The page, or null when [before] names no correction of the org.
     */
    suspend fun history(orgId: Uuid, memberId: Uuid?, before: Uuid?): ReviewPage<ReconciledUsage>?

    /**
     * One hold under review or its correction.
     *
     * @param orgId The organization.
     * @param id The hold.
     *
     * @return The review, or null when the id is neither.
     */
    suspend fun review(orgId: Uuid, id: Uuid): UsageReview?

    /**
     * Replaces an expired hold's charge by [change]'s total, under the lock of the org's admissions, and records it.
     *
     * @param orgId The organization.
     * @param id The hold.
     * @param actor The administrator.
     * @param change The checked correction ([UsageReconciliationRules.validate]).
     *
     * @return What came of it.
     */
    suspend fun reconcile(orgId: Uuid, id: Uuid, actor: Uuid, change: UsageReconciliationChange): ReconcileOutcome
}

/** The bounds of a correction (TeamCode's `CheckUsageReconciliationUseCase`), in USD millionths. */
object UsageReconciliationRules {
    /** The largest total: $100 000 000. */
    const val MAX_MICROS: Long = 100_000_000_000_000

    /** The longest reference or reason. */
    const val MAX_TEXT: Int = 240

    /** The items of a page. */
    const val PAGE_SIZE: Int = 50

    private val PROVIDER_REQUEST_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

    /**
     * Checks a correction and trims its texts.
     *
     * @param change The correction as sent.
     *
     * @return It, trimmed.
     *
     * @throws InvalidUsageReconciliationException Out of bounds.
     */
    fun validate(change: UsageReconciliationChange): UsageReconciliationChange {
        if (change.expectedMicros !in 0..MAX_MICROS || change.micros !in 0..MAX_MICROS) {
            throw InvalidUsageReconciliationException(
                "Give a verified total between 0 and $MAX_MICROS USD millionths.",
            )
        }
        if (change.expectedProviderRequestId?.let { !providerRequestId(it) } == true) {
            throw InvalidUsageReconciliationException("Use the provider request id shown in the review.")
        }
        return change.copy(reference = text(change.reference), reason = text(change.reason))
    }

    /** Whether [value] is a provider request id as the gateway would keep one: bounded, opaque, one token. */
    fun providerRequestId(value: String): Boolean = PROVIDER_REQUEST_ID.matches(value)

    /** Whether [stored] is the same correction as [change] (its retry): same view, same total, same evidence. */
    fun sameCorrection(stored: ReconciledUsage, change: UsageReconciliationChange): Boolean =
        stored.previousMicros == change.expectedMicros && stored.micros == change.micros &&
            stored.source == change.source && stored.reference == change.reference &&
            stored.reason == change.reason && stored.providerRequestId == change.expectedProviderRequestId

    private fun text(value: String): String = value.trim().also { trimmed ->
        if (trimmed.length !in 1..MAX_TEXT || trimmed.any { it < ' ' || it == '\u007f' }) {
            throw InvalidUsageReconciliationException(
                "Give a single-line reference and reason of 1 to $MAX_TEXT characters, without secrets or content.",
            )
        }
    }
}

/**
 * A correction is out of bounds, or a filter or cursor malformed (400 `invalid_reconciliation`).
 *
 * @param message What is wrong, safe for the client.
 */
class InvalidUsageReconciliationException(message: String) :
    OstromException(ErrorKind.INVALID_REQUEST, message, "invalid_reconciliation")

/** The charge or request id changed since it was shown (409 `stale_reconciliation`). */
class StaleUsageReconciliationException :
    OstromException(
        ErrorKind.CONFLICT,
        "The charge or the request identity changed: review it again before correcting it.",
        "stale_reconciliation",
    )

/** Another correction already resolved this hold (409 `reconciled`). */
class UsageAlreadyReconciledException :
    OstromException(
        ErrorKind.CONFLICT,
        "Another correction already resolved this call: read its reconciliation.",
        "reconciled",
    )

/** No hold of that id under review nor corrected, or no such cursor (404). */
class UsageReviewNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Usage review not found.")
