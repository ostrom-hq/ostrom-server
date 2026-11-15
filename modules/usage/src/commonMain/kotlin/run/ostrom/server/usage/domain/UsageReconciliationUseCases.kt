// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.domain

import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.secrets.domain.Caller
import kotlin.uuid.Uuid

/**
 * What a review route answers, with the login of each member it names.
 *
 * @property value The page, review or record.
 * @property logins The login of each member id it names that is still a member.
 */
data class Named<T>(val value: T, val logins: Map<Uuid, String>)

/** Lists the unknown usage awaiting review (TeamCode's `pending`). */
interface ListPendingUsageUseCase {
    /**
     * Reads a page, newest first.
     *
     * @param caller An owner or admin.
     * @param member Only this member's (`OrgMember.id`).
     * @param before The id of the last item of the previous page.
     *
     * @return The page.
     *
     * @throws run.ostrom.server.secrets.domain.AdminRequiredException When the caller is not an admin.
     * @throws InvalidUsageReconciliationException A malformed filter or cursor.
     * @throws UsageReviewNotFoundException [before] names no hold of the org.
     */
    suspend operator fun invoke(caller: Caller, member: String?, before: String?): Named<ReviewPage<PendingUsage>>
}

/** Lists the corrections made (TeamCode's `history`). */
interface ListUsageReconciliationsUseCase {
    /**
     * Reads a page, newest first.
     *
     * @param caller An owner or admin.
     * @param member Only this member's (`OrgMember.id`).
     * @param before The id of the last item of the previous page.
     *
     * @return The page.
     */
    suspend operator fun invoke(caller: Caller, member: String?, before: String?): Named<ReviewPage<ReconciledUsage>>
}

/** Reads one hold under review, or its correction. */
interface GetUsageReviewUseCase {
    /**
     * Reads it.
     *
     * @param caller An owner or admin.
     * @param id The hold's id.
     *
     * @return The review.
     *
     * @throws UsageReviewNotFoundException The id is neither.
     */
    suspend operator fun invoke(caller: Caller, id: String): Named<UsageReview>
}

/** Replaces an unknown charge with what the provider billed. */
interface ReconcileUsageUseCase {
    /**
     * Corrects it, once; the same correction again answers the original record.
     *
     * @param caller An owner or admin.
     * @param id The hold's id.
     * @param change The correction.
     *
     * @return The record, and whether this call wrote it.
     *
     * @throws InvalidUsageReconciliationException Out of bounds.
     * @throws StaleUsageReconciliationException The charge shown is no longer the hold's.
     * @throws UsageAlreadyReconciledException Another correction resolved it.
     * @throws UsageReviewNotFoundException No such hold under review.
     */
    suspend operator fun invoke(
        caller: Caller,
        id: String,
        change: UsageReconciliationChange,
    ): Named<ReconcileOutcome.Reconciled>
}

private fun uuidOf(value: String, what: String): Uuid =
    Uuid.parseOrNull(value) ?: throw InvalidUsageReconciliationException("$what is an id.")

private suspend fun <T> MemberLogins.named(caller: Caller, value: T, members: Collection<Uuid>): Named<T> {
    val ids = members.toSet()
    return Named(value, if (ids.isEmpty()) emptyMap() else loginsOf(caller.orgId, ids))
}

/**
 * [ListPendingUsageUseCase] on the repository.
 *
 * @param repository The reviews.
 * @param logins The members' logins.
 */
class ListPendingUsageUseCaseImpl(
    private val repository: UsageReconciliationRepository,
    private val logins: MemberLogins,
) : ListPendingUsageUseCase {
    override suspend fun invoke(caller: Caller, member: String?, before: String?): Named<ReviewPage<PendingUsage>> {
        caller.requireAdmin()
        val memberId = member?.let { uuidOf(it, "member") }
        val cursor = before?.let { uuidOf(it, "before") }
        val page = repository.pending(caller.orgId, memberId, cursor) ?: throw UsageReviewNotFoundException()
        return logins.named(caller, page, page.items.map { it.memberId })
    }
}

/**
 * [ListUsageReconciliationsUseCase] on the repository.
 *
 * @param repository The reviews.
 * @param logins The members' logins.
 */
class ListUsageReconciliationsUseCaseImpl(
    private val repository: UsageReconciliationRepository,
    private val logins: MemberLogins,
) : ListUsageReconciliationsUseCase {
    override suspend fun invoke(
        caller: Caller,
        member: String?,
        before: String?,
    ): Named<ReviewPage<ReconciledUsage>> {
        caller.requireAdmin()
        val memberId = member?.let { uuidOf(it, "member") }
        val cursor = before?.let { uuidOf(it, "before") }
        val page = repository.history(caller.orgId, memberId, cursor) ?: throw UsageReviewNotFoundException()
        return logins.named(caller, page, page.items.flatMap { listOf(it.memberId, it.reconciledBy) })
    }
}

/**
 * [GetUsageReviewUseCase] on the repository.
 *
 * @param repository The reviews.
 * @param logins The members' logins.
 */
class GetUsageReviewUseCaseImpl(
    private val repository: UsageReconciliationRepository,
    private val logins: MemberLogins,
) : GetUsageReviewUseCase {
    override suspend fun invoke(caller: Caller, id: String): Named<UsageReview> {
        caller.requireAdmin()
        // The id is a resource identifier: a malformed one names nothing.
        val holdId = Uuid.parseOrNull(id) ?: throw UsageReviewNotFoundException()
        val review = repository.review(caller.orgId, holdId) ?: throw UsageReviewNotFoundException()
        val members = listOfNotNull(
            review.pending?.memberId,
            review.reconciliation?.memberId,
            review.reconciliation?.reconciledBy,
        )
        return logins.named(caller, review, members)
    }
}

/**
 * [ReconcileUsageUseCase] on the repository.
 *
 * @param repository The reviews.
 * @param logins The members' logins.
 */
class ReconcileUsageUseCaseImpl(
    private val repository: UsageReconciliationRepository,
    private val logins: MemberLogins,
) : ReconcileUsageUseCase {
    override suspend fun invoke(
        caller: Caller,
        id: String,
        change: UsageReconciliationChange,
    ): Named<ReconcileOutcome.Reconciled> {
        caller.requireAdmin()
        val holdId = Uuid.parseOrNull(id) ?: throw UsageReviewNotFoundException()
        val checked = UsageReconciliationRules.validate(change)
        return when (val outcome = repository.reconcile(caller.orgId, holdId, caller.memberId, checked)) {
            is ReconcileOutcome.Reconciled -> {
                val record = outcome.reconciliation
                logins.named(caller, outcome, listOf(record.memberId, record.reconciledBy))
            }
            ReconcileOutcome.NotFound -> throw UsageReviewNotFoundException()
            ReconcileOutcome.Stale -> throw StaleUsageReconciliationException()
            ReconcileOutcome.AlreadyReconciled -> throw UsageAlreadyReconciledException()
        }
    }
}
