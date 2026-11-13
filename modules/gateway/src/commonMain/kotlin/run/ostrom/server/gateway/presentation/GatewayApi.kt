// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gateway.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `/api/v1/policy/models`: the org's model policy (contract `getModelPolicy`, `setModelPolicy`). */
@Serializable
@Resource("/api/v1/policy/models")
class ModelPolicyApi

/** `/api/v1/inference/settings`: provider keys and shared accounts (an addition to the contract). */
@Serializable
@Resource("/api/v1/inference/settings")
class InferenceSettingsApi

/** `/api/v1/inference/queue`: the calls waiting in the admission queue (contract `getInferenceQueue`). */
@Serializable
@Resource("/api/v1/inference/queue")
class InferenceQueueApi

/**
 * `/api/v1/inference/requests/{id}/cancel`: stops one of the caller's inference calls (contract
 * `cancelInferenceRequest`).
 *
 * @property id The call's `x-ostrom-client-request-id`.
 */
@Serializable
@Resource("/api/v1/inference/requests/{id}/cancel")
class InferenceRequestCancelApi(val id: String)

/**
 * The waiting calls (contract `InferenceQueue`).
 *
 * @property waiting The org's.
 * @property waitingMine The caller's among them.
 */
@Serializable
data class InferenceQueueDto(val waiting: Int, val waitingMine: Int)

/**
 * The model policy (contract `ModelPolicy`).
 *
 * @property allowed Model names or prefixes every member may use; empty for every model.
 * @property defaultModel Sent when an agent names no model (`default` in JSON).
 * @property byMember A member's own list, by member id.
 */
@Serializable
data class ModelPolicyDto(
    val allowed: List<String> = emptyList(),
    @SerialName("default") val defaultModel: String? = null,
    val byMember: Map<String, List<String>> = emptyMap(),
)

/**
 * The org's inference settings. Keys are named by the org secret holding them, never sent.
 *
 * @property anthropicKeySecret Anthropic API key, when no shared Claude account serves.
 * @property openaiKeySecret OpenAI API key.
 * @property openrouterKeySecret OpenRouter key (models named `vendor/model`).
 * @property useSharedAccounts Whether shared subscription accounts serve first.
 * @property memberConcurrency The most calls a member runs at once, by member id.
 */
@Serializable
data class InferenceSettingsDto(
    val anthropicKeySecret: String? = null,
    val openaiKeySecret: String? = null,
    val openrouterKeySecret: String? = null,
    val useSharedAccounts: Boolean = true,
    val memberConcurrency: Map<String, Int> = emptyMap(),
)
