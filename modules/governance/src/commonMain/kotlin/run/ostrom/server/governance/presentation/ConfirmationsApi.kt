// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.governance.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// The routes and bodies of `ostrom-core/api/openapi.yaml` (operations `getGovernanceConfirmation` and
// `answerGovernanceConfirmation`).

/**
 * One confirmation (contract `getGovernanceConfirmation`).
 *
 * @property id The confirmation.
 * @property wait How many seconds to wait for its answer, 0 to 30.
 */
@Serializable
@Resource("/api/v1/confirmations/{id}")
class ConfirmationApi(val id: String, val wait: Int? = null) {
    /**
     * Its answer (contract `answerGovernanceConfirmation`).
     *
     * @property parent The confirmation.
     */
    @Serializable
    @Resource("answer")
    class Answer(val parent: ConfirmationApi)
}

/**
 * The body of `POST /api/v1/confirmations/{id}/answer`.
 *
 * @property answer `confirm` or `refuse`.
 */
@Serializable
data class AnswerConfirmationRequest(val answer: String)

/**
 * A member of a confirmation (contract `GovernanceConfirmationPerson`).
 *
 * @property memberId Their membership.
 * @property login Their login.
 * @property name Their name.
 */
@Serializable
data class ConfirmationPersonDto(val memberId: String, val login: String? = null, val name: String? = null)

/**
 * The personal token that asked (contract `GovernanceConfirmationToken`).
 *
 * @property id Its id.
 * @property name The name its owner gave it when it asked.
 */
@Serializable
data class ConfirmationTokenDto(val id: String, val name: String)

/**
 * Why a confirmed write was not written (contract `ApiError`).
 *
 * @property type The stable key.
 * @property message The message.
 */
@Serializable
data class ConfirmationErrorDto(val type: String, val message: String)

/**
 * A confirmation (contract `GovernanceConfirmation`).
 *
 * @property id The id.
 * @property kind The write.
 * @property status Where it stands.
 * @property method The method of the request that asked.
 * @property path Its path.
 * @property scope The governance scope.
 * @property summary One line.
 * @property before What it replaces, JSON null for nothing: always sent.
 * @property after What it writes, JSON null for nothing: always sent.
 * @property requestedBy Who asked.
 * @property credential `admin`, `device` or `api_token`.
 * @property token The personal token that asked, for `api_token`.
 * @property createdAt When it was asked (ISO 8601).
 * @property expiresAt When it expires (ISO 8601).
 * @property answeredBy Who answered.
 * @property answeredAt When (ISO 8601).
 * @property result What the confirmed write returned.
 * @property error Why the confirmed write was not written.
 */
@Serializable
data class ConfirmationDto(
    val id: String,
    val kind: String,
    val status: String,
    val method: String,
    val path: String,
    val scope: String,
    val summary: String,
    val before: JsonElement,
    val after: JsonElement,
    val requestedBy: ConfirmationPersonDto,
    val credential: String,
    val token: ConfirmationTokenDto? = null,
    val createdAt: String,
    val expiresAt: String,
    val answeredBy: ConfirmationPersonDto? = null,
    val answeredAt: String? = null,
    val result: JsonElement? = null,
    val error: ConfirmationErrorDto? = null,
)
