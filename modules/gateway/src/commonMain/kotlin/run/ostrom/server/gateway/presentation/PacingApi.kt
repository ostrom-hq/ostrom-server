// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/** `/api/v1/policy/pacing` (`getPacingSettings`, `setPacingSettings`, `capacity.pacing.v1`). */
@Serializable
@Resource("/api/v1/policy/pacing")
class PacingSettingsApi

/** `/api/v1/directives/preview` (`previewDirective`). */
@Serializable
@Resource("/api/v1/directives/preview")
class DirectivePreviewApi

/** `/api/v1/directives/current` (`getCurrentDirective`, `getSessionDirective`). */
@Serializable
@Resource("/api/v1/directives/current")
class DirectiveCurrentApi

/** `/api/v1/directives/claim` (`claimDirective`). */
@Serializable
@Resource("/api/v1/directives/claim")
class DirectiveClaimApi

/** `/api/v1/directives/receipt` (`acknowledgeDirective`). */
@Serializable
@Resource("/api/v1/directives/receipt")
class DirectiveReceiptApi

/** The contract's `PacingSettings`: a field omitted from a change keeps its stored value. */
@Serializable
data class PacingSettingsDto(val observationOnly: Boolean? = null, val autoPoolSize: Boolean? = null)

/** The contract's `Directive`. */
@Serializable
data class DirectiveDto(
    val id: String,
    val pace: String,
    val maxConcurrent: Int,
    val minIntervalMillis: Long,
    val message: String,
    val createdAt: String,
    val expiresAt: String,
)

/** The contract's `PacingPreview`. */
@Serializable
data class PacingPreviewDto(val settings: PacingSettingsDto, val directive: DirectiveDto? = null)

/** The contract's `DirectiveResponse`. */
@Serializable
data class DirectiveResponseDto(val directive: DirectiveDto? = null)

/** The contract's `DirectiveScope`. */
@Serializable
data class DirectiveScopeDto(val sessionId: String)

/** The contract's `DirectiveClaimRequest`. */
@Serializable
data class DirectiveClaimRequestDto(val sessionId: String, val stage: String)

/** The contract's `DirectiveClaim`. */
@Serializable
data class DirectiveClaimDto(val directive: DirectiveDto? = null, val claimId: String? = null)

/** The contract's `DirectiveReceipt`. */
@Serializable
data class DirectiveReceiptDto(
    val id: String,
    val stage: String,
    val sessionId: String? = null,
    val claimId: String? = null,
)
