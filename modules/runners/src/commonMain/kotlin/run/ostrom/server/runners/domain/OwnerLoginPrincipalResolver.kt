// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.SignedInDevices
import run.ostrom.server.kernel.auth.TokenAuthenticator
import kotlin.uuid.Uuid

/**
 * [RunnerPrincipalResolver] on a remote session's host (TeamCode's `AuthenticateRunnerUseCase.host`): a remote session
 * has no pod, so its host presents its owner's own sign-in, accepted only for a person's CLI sign-in from the session's
 * device, for the session's current, live execution. Anything else is no credential of this resolver, so the call is
 * fenced (`409 execution_fenced`) and the host exits. Revoking the sign-in or removing the device stops the host at its
 * next call.
 *
 * The credential alone names the device, not the session (one device hosts several): [resolve] with the call's claim
 * decides; without it, the credential is known when the device hosts a live execution of its owner's, the first one
 * found, which only the check before a call's body is read uses (S2-46).
 *
 * @param logins Resolves a sign-in.
 * @param devices The devices of sign-ins.
 * @param orgs The member a sign-in is in the session's organization.
 * @param repository The sessions.
 */
class OwnerLoginPrincipalResolver(
    private val logins: TokenAuthenticator,
    private val devices: SignedInDevices,
    private val orgs: PrincipalOrgResolver,
    private val repository: CloudSessionsRepository,
) : RunnerPrincipalResolver {
    override suspend fun resolve(credential: String): RunnerExecution? {
        val (principal, device) = host(credential) ?: return null
        return repository.hostedOn(device, live = true)
            .firstNotNullOfOrNull { execution(principal, device, it.id, null) }
    }

    override suspend fun resolve(credential: String, sessionId: Uuid, epoch: Long): RunnerExecution? {
        val (principal, device) = host(credential) ?: return null
        return execution(principal, device, sessionId, epoch)
    }

    /** A person's CLI sign-in from a device, live session or not: a heartbeat it sends is fenced, never refused. */
    override suspend fun knows(credential: String): Boolean = host(credential) != null

    /** The person's sign-in and its device, or null for any other credential. */
    private suspend fun host(credential: String): Pair<AuthenticatedPrincipal, Uuid>? {
        val principal = logins.authenticate(credential)?.takeIf { it.kind == PrincipalKind.USER && it.signedIn }
        return principal?.let { signedIn -> devices.deviceOf(signedIn)?.let { signedIn to it } }
    }

    /** The execution [epoch] (its current one when null) of [sessionId], when it is its live one on [device]. */
    @Suppress("ReturnCount") // Each check refuses the same way.
    private suspend fun execution(
        principal: AuthenticatedPrincipal,
        device: Uuid,
        sessionId: Uuid,
        epoch: Long?,
    ): RunnerExecution? {
        val session = repository.getById(sessionId) ?: return null
        if (session.kind != SessionKind.REMOTE || session.host?.deviceId != device) return null
        val live = !session.state.isFinal && session.executionStartedAt != null
        if (!live || session.epoch != (epoch ?: session.epoch)) return null
        val member = orgs.resolve(principal, session.orgId) ?: return null
        if (member.orgId != session.orgId || member.memberId.toString() != session.ownerId) return null
        return RunnerExecution(session.orgId, session.id, session.epoch)
    }
}
