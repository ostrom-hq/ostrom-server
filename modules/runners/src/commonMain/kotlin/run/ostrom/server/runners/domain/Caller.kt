// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import kotlin.uuid.Uuid

/**
 * The member a request acts for, inside their organization.
 *
 * A cloud session write may name another member in `Ostrom-On-Behalf-Of` (`runners.cloud.onbehalf.v1`, TeamCode's
 * `CloudActor`): [memberId] is then that effective member, [initiatorId] the caller, and the call keeps the caller's
 * rights ([admin]) ([ResolveCloudActorUseCase]).
 *
 * @property orgId The caller's organization; a session of another organization does not exist for them.
 * @property memberId The member the request acts for: the caller, or the effective member they named.
 * @property admin Whether the caller is an owner or an admin of the organization.
 * @property initiatorId The member who made the request; [memberId] unless they named another one.
 * @property credentialId The id of the credential the request presented (never the token itself); null when the
 *   request came from the server itself (a workflow run, an automation).
 * @property principal The credential the request presented, for the audit log; null when the request came from the
 *   server itself, which journals nothing.
 * @property memberAdmin Whether [memberId] is an owner or an admin: [admin] for the caller themselves, the effective
 *   member's role otherwise (a profile's assignment resolves for the owner, TeamCode's create).
 */
data class Caller(
    val orgId: Uuid,
    val memberId: String,
    val admin: Boolean = false,
    val initiatorId: String = memberId,
    val credentialId: String? = null,
    val principal: AuthenticatedPrincipal? = null,
    val memberAdmin: Boolean = admin,
) {
    /** Whether the request acts for another member than the one who made it. */
    val onBehalf: Boolean get() = memberId != initiatorId

    /** Who created a session for this request (TeamCode's `CloudActor.attribution`). */
    val attribution: CloudAttribution get() = CloudAttribution(initiatorId, credentialId, memberId)
}
