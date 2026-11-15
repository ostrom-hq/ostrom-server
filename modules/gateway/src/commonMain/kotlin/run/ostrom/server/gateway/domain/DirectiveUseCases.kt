// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.domain

import run.ostrom.server.secrets.domain.Caller

/**
 * The controller's advice for the caller, also in observation mode (`previewDirective`): informational, never
 * delivered by itself.
 *
 * @property settings The org's controller settings.
 * @property directive The member-wide advice, if any.
 */
data class PacingPreview(val settings: PacingSettings, val directive: AgentDirective?)

/** The member's advice as TeamCode's `DirectiveService` gives it (`capacity.pacing.v1`). */
interface DirectiveUseCases {
    /** The settings and the member-wide advice, whatever the mode. */
    suspend fun preview(caller: Caller): PacingPreview

    /** The member-wide advice, or the session's when [session] is given; none in observation mode. */
    suspend fun current(caller: Caller, session: String?): AgentDirective?

    /** Claims the delivery of the session's new advice at [stage] for [tokenId]; none in observation mode. */
    suspend fun claim(caller: Caller, session: String, stage: DeliveryStage, tokenId: String): DirectiveDelivery?

    /**
     * Records that advice [id] reached [stage]: a session's receipt needs its claim, a member-wide one has none.
     *
     * @throws InvalidDirectiveRequestException `invalid_claim` when only one of [session] and [claimId] is given.
     */
    @Suppress("LongParameterList")
    suspend fun acknowledge(
        caller: Caller,
        id: String,
        stage: DeliveryStage,
        session: String?,
        claimId: String?,
        tokenId: String,
    )
}

/**
 * [DirectiveUseCases] on the stored advice and the settings (TeamCode's `SqlDirectiveDelivery`): a short delivery lease
 * avoids concurrent duplicates without claiming that the agent obeyed.
 *
 * @param directives The stored advice.
 * @param settings The controller's settings.
 */
class DirectiveUseCasesImpl(
    private val directives: DirectiveRepository,
    private val settings: PacingSettingsRepository,
) : DirectiveUseCases {
    override suspend fun preview(caller: Caller): PacingPreview =
        PacingPreview(settings.get(caller.orgId), directives.current(caller.orgId, caller.memberId, null))

    override suspend fun current(caller: Caller, session: String?): AgentDirective? {
        session?.let(::requireSessionId)
        return if (settings.get(caller.orgId).observationOnly) {
            null
        } else {
            directives.current(caller.orgId, caller.memberId, session)
        }
    }

    override suspend fun claim(
        caller: Caller,
        session: String,
        stage: DeliveryStage,
        tokenId: String,
    ): DirectiveDelivery? {
        requireSessionId(session)
        return if (settings.get(caller.orgId).observationOnly) {
            null
        } else {
            directives.claim(caller.orgId, caller.memberId, session, stage, tokenId)
        }
    }

    @Suppress("LongParameterList")
    override suspend fun acknowledge(
        caller: Caller,
        id: String,
        stage: DeliveryStage,
        session: String?,
        claimId: String?,
        tokenId: String,
    ) {
        session?.let(::requireSessionId)
        if ((session == null) != (claimId == null)) {
            throw InvalidDirectiveRequestException("invalid_claim", "Session receipts require their delivery claim.")
        }
        directives.acknowledge(caller.orgId, caller.memberId, id, stage, session, claimId, tokenId)
    }
}
