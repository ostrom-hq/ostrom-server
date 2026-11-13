// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts.infrastructure

import run.ostrom.server.artifacts.domain.ViewTicket
import run.ostrom.server.artifacts.domain.ViewerChecks
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.TokenAuthenticator
import kotlin.uuid.Uuid

/**
 * [ViewerChecks] on the kernel's ports: the identity module says whether the credential is live, the orgs module
 * whether the member is still in the organization. Without the orgs module nobody is (fail closed).
 *
 * @param authenticator The identity module's authenticator.
 * @param members The organizations' members, or null when no module binds them.
 */
internal class KernelViewerChecks(
    private val authenticator: TokenAuthenticator,
    private val members: MemberDirectory?,
) : ViewerChecks {
    override suspend fun stillSignedIn(orgId: Uuid, ticket: ViewTicket): Boolean =
        members?.isActiveMember(orgId, ticket.memberId) == true &&
            authenticator.isLive(ticket.subjectId, ticket.credentialId)
}
