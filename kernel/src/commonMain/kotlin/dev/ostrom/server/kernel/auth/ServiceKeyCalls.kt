// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.auth

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import kotlin.uuid.Uuid

/**
 * One call a service key made, as its organization's audit log keeps it.
 *
 * @property keyId The key: its token's id, the subject of its principal.
 * @property orgId The organization it acts in, its own.
 * @property operation The contract's operation (`reportCheck`, `listRules`…).
 * @property target What the call was about: the request's path.
 */
data class ServiceKeyCall(val keyId: String, val orgId: Uuid, val operation: String, val target: String)

/**
 * Writes every call of a service key to its organization's signed audit log, in the key's name. The identity module
 * binds it; until it does, [serverKoin][dev.ostrom.server.kernel.module.serverKoin] binds [RejectingServiceKeyAudit],
 * so no service key acts unaudited.
 */
interface ServiceKeyAudit {
    /**
     * Records the call, before it acts.
     *
     * @param call The call.
     */
    suspend fun record(call: ServiceKeyCall)
}

/** Refuses every service key: the default until the identity module binds the audit. */
class RejectingServiceKeyAudit : ServiceKeyAudit {
    override suspend fun record(call: ServiceKeyCall): Unit = throw NoOrganizationCredentialException()
}

/**
 * Admits a service key on one of the few routes a CI calls (`reportCheck`, `signWorkflowReceipt`, `listRules`,
 * `getRule`): it acts in its own organization, the one its key is bound to, whatever `Ostrom-Org` says (a key is no
 * member, so it never names another), with [scope], and the call is audited in its name before it acts. Every other
 * route keeps refusing it: no route admits a service key unless it calls this.
 *
 * @param audit The audit.
 * @param scope The scope the route needs from a key.
 * @param operation The contract's operation, for the audit.
 * @param target The request's path, for the audit.
 *
 * @return The key's organization; null when the caller is not a service key (the route resolves it as before).
 *
 * @throws OstromException 403 when the key lacks [scope], or is bound to no organization.
 */
suspend fun AuthenticatedPrincipal.admitServiceKey(
    audit: ServiceKeyAudit,
    scope: String,
    operation: String,
    target: String,
): Uuid? {
    if (kind != PrincipalKind.SERVICE) return null
    if (scope !in scopes) throw OstromException(ErrorKind.FORBIDDEN, "This credential does not allow $scope.")
    val own = orgId?.let(Uuid::parseOrNull) ?: throw NoOrganizationCredentialException()
    audit.record(ServiceKeyCall(subjectId, own, operation, target))
    return own
}
