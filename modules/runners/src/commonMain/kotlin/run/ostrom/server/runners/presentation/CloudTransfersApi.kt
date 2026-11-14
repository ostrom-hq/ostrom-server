// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/**
 * `GET /api/v1/cloud/transfers` (`listCloudTransfers`): the caller's transfers, newest first.
 *
 * @property direction `incoming`, `outgoing` or `all`.
 * @property session Only the transfers of this session.
 * @property before The cursor of the previous page.
 * @property limit The page size, 1 to 50.
 */
@Serializable
@Resource("/api/v1/cloud/transfers")
class CloudTransfersApi(
    val direction: String? = null,
    val session: String? = null,
    val before: String? = null,
    val limit: Int? = null,
) {
    /**
     * `GET /api/v1/cloud/transfers/{id}`.
     *
     * @property parent The collection.
     * @property id The transfer.
     */
    @Serializable
    @Resource("{id}")
    class Id(val parent: CloudTransfersApi = CloudTransfersApi(), val id: String) {
        /** `POST /api/v1/cloud/transfers/{id}/accept`. */
        @Serializable
        @Resource("accept")
        class Accept(val parent: Id)

        /** `POST /api/v1/cloud/transfers/{id}/decline`. */
        @Serializable
        @Resource("decline")
        class Decline(val parent: Id)

        /** `POST /api/v1/cloud/transfers/{id}/cancel`. */
        @Serializable
        @Resource("cancel")
        class Cancel(val parent: Id)
    }
}

/** `GET /api/v1/cloud/transfer-recipients` (`listCloudTransferRecipients`). */
@Serializable
@Resource("/api/v1/cloud/transfer-recipients")
class CloudTransferRecipientsApi

/** `POST /api/v1/cloud/sessions/{id}/transfer` (`transferCloudSession`). */
@Serializable
@Resource("/api/v1/cloud/sessions/{id}/transfer")
class CloudSessionTransferApi(val id: String)

/** The contract's `CreateCloudTransferRequest`. */
@Serializable
data class CreateCloudTransferRequest(val toMemberId: String, val resume: Boolean = false)

/** The contract's `AcceptCloudTransferRequest`. */
@Serializable
data class AcceptCloudTransferRequest(val resume: Boolean? = null)

/** The contract's `CloudTransfer`. */
@Serializable
data class CloudTransferResponse(
    val id: String,
    val sessionId: String,
    val fromMemberId: String,
    val toMemberId: String,
    val state: String,
    val initiatedBy: String,
    val decidedBy: String? = null,
    val resume: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val expiresAt: String? = null,
    val decidedAt: String? = null,
    val completedAt: String? = null,
    val atEpoch: Long? = null,
    val failure: SessionFailureDto? = null,
)

/** The contract's `CloudTransferPage`. */
@Serializable
data class CloudTransferPageResponse(val items: List<CloudTransferResponse>, val next: String? = null)

/** The contract's `CloudTransferRecipient`. */
@Serializable
data class CloudTransferRecipientResponse(val id: String, val login: String, val administrator: Boolean)

/** The contract's `CloudTransferRecipients`. */
@Serializable
data class CloudTransferRecipientsResponse(val recipients: List<CloudTransferRecipientResponse>)
