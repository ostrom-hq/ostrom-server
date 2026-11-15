// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/**
 * `/api/v1/usage/pending`: the unknown usage awaiting review (contract `listPendingUsage`,
 * `capacity.usagereconciliation.v1`).
 *
 * @property member Only this member's (`OrgMember.id`).
 * @property before The `id` of the last item of the previous page.
 */
@Serializable
@Resource("/api/v1/usage/pending")
class PendingUsageApi(val member: String? = null, val before: String? = null)

/**
 * `/api/v1/usage/reconciliations`: the corrections made (contract `listUsageReconciliations`).
 *
 * @property member Only this member's (`OrgMember.id`).
 * @property before The `id` of the last item of the previous page.
 */
@Serializable
@Resource("/api/v1/usage/reconciliations")
class UsageReconciliationsApi(val member: String? = null, val before: String? = null)

/**
 * `/api/v1/usage/reservations/{id}`: one hold under review, or its correction (contract `getUsageReview`).
 *
 * @property id The hold's id, the call's.
 */
@Serializable
@Resource("/api/v1/usage/reservations/{id}")
class UsageReservationApi(val id: String) {
    /**
     * `/reconcile`: replaces its unknown charge (contract `reconcileUsage`).
     *
     * @property parent The hold.
     */
    @Serializable
    @Resource("reconcile")
    class Reconcile(val parent: UsageReservationApi)
}

/**
 * A model call whose final usage is unknown (contract `PendingUsage`).
 *
 * @property id The call's id, the hold's.
 * @property member The member's login (their id once they left).
 * @property micros The charge kept, USD millionths.
 * @property heldAt When it was held.
 * @property leaseExpiredAt When its hold ran out.
 * @property workflowId The workflow its launch played.
 * @property launchId Its launch.
 * @property providerRequestId The provider's request id, when captured.
 */
@Serializable
data class PendingUsageDto(
    val id: String,
    val member: String,
    val micros: Long,
    val heldAt: String,
    val leaseExpiredAt: String,
    val workflowId: String? = null,
    val launchId: String? = null,
    val providerRequestId: String? = null,
)

/**
 * A final correction of an unknown charge (contract `UsageReconciliation`).
 *
 * @property id The hold corrected.
 * @property member The member's login.
 * @property workflowId Its workflow then.
 * @property launchId Its launch then.
 * @property providerRequestId The provider request id then.
 * @property heldAt When it was held.
 * @property previousMicros The charge before.
 * @property micros The verified total.
 * @property source `PROVIDER_RECORD` or `PROVIDER_CONFIRMATION`.
 * @property reference The pointer to the evidence.
 * @property reason Why.
 * @property reconciledBy The administrator's login.
 * @property reconciledAt When.
 */
@Serializable
data class UsageReconciliationDto(
    val id: String,
    val member: String,
    val workflowId: String? = null,
    val launchId: String? = null,
    val providerRequestId: String? = null,
    val heldAt: String,
    val previousMicros: Long,
    val micros: Long,
    val source: String,
    val reference: String,
    val reason: String,
    val reconciledBy: String,
    val reconciledAt: String,
)

/**
 * A page of unknown usage.
 *
 * @property items Newest first.
 * @property next The `before` of the following page.
 */
@Serializable
data class PendingUsagePage(val items: List<PendingUsageDto>, val next: String? = null)

/**
 * A page of corrections.
 *
 * @property items Newest first.
 * @property next The `before` of the following page.
 */
@Serializable
data class UsageReconciliationsPage(val items: List<UsageReconciliationDto>, val next: String? = null)

/**
 * One hold under review or its correction (contract `UsageReview`): exactly one is present.
 *
 * @property pending While unknown.
 * @property reconciliation Once corrected.
 */
@Serializable
data class UsageReviewDto(val pending: PendingUsageDto? = null, val reconciliation: UsageReconciliationDto? = null)

/**
 * The body of `reconcileUsage`.
 *
 * @property expectedMicros The charge as the reviewer saw it.
 * @property expectedProviderRequestId The provider request id as the reviewer saw it.
 * @property micros The verified total.
 * @property source `PROVIDER_RECORD` or `PROVIDER_CONFIRMATION`.
 * @property reference A non-secret pointer to the evidence.
 * @property reason Why.
 */
@Serializable
data class ReconcileUsageRequest(
    val expectedMicros: Long,
    val micros: Long,
    val source: String,
    val reference: String,
    val reason: String,
    val expectedProviderRequestId: String? = null,
)
