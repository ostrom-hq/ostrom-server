// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.governance.domain

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.uuid.Uuid

/** Reads a confirmation, waiting for its answer when asked (the CLI waits here). */
interface GetConfirmationUseCase {
    /**
     * Reads it.
     *
     * @param viewer Who reads: its requester (any of their credentials) or an owner or an admin.
     * @param id The confirmation.
     * @param wait How long a pending confirmation may be waited on for its answer, within 0 and
     *   [ConfirmationRules.MAX_WAIT]: the read answers as soon as it is no longer pending.
     *
     * @return The confirmation, as it stands.
     *
     * @throws ConfirmationNotFoundException When the viewer's organization has no such confirmation, or the viewer may
     *   not read it.
     */
    suspend operator fun invoke(
        viewer: AuthenticatedPrincipal,
        id: String,
        wait: Duration = Duration.ZERO,
    ): Confirmation
}

/**
 * [GetConfirmationUseCase] on the repository: each look is a short read of its own, and between two the wait holds no
 * connection, woken early when this instance answers it.
 *
 * @param repository The confirmations.
 * @param members Resolves the viewer's membership.
 * @param expire Writes the expiry of the overdue ones first.
 * @param waits Wakes the waiting read.
 * @param clock The time.
 */
class GetConfirmationUseCaseImpl(
    private val repository: ConfirmationsRepository,
    private val members: PrincipalOrgResolver,
    private val expire: ExpireConfirmationsUseCase,
    private val waits: ConfirmationWaits,
    private val clock: Clock,
) : GetConfirmationUseCase {
    override suspend fun invoke(viewer: AuthenticatedPrincipal, id: String, wait: Duration): Confirmation {
        // A person only: an agent, a device or a service never reads what an owner is asked to confirm.
        if (viewer.kind != PrincipalKind.USER) throw ConfirmationNotFoundException()
        val member = members.resolve(viewer, null) ?: throw ConfirmationNotFoundException()
        val until = clock.now() + wait.coerceIn(Duration.ZERO, ConfirmationRules.MAX_WAIT)
        var confirmation = read(member.orgId, id)
        val mayRead = confirmation.requestedBy.userId == viewer.subjectId || member.role in ConfirmationRules.MANAGERS
        if (!mayRead) throw ConfirmationNotFoundException()
        while (confirmation.status == ConfirmationStatus.PENDING && clock.now() < until) {
            waits.await(id, minOf(until - clock.now(), ConfirmationRules.LOOK_EVERY))
            confirmation = read(member.orgId, id)
        }
        return confirmation
    }

    private suspend fun read(orgId: Uuid, id: String): Confirmation {
        expire.inOrganization(orgId)
        return repository.get(orgId, id) ?: throw ConfirmationNotFoundException()
    }
}
