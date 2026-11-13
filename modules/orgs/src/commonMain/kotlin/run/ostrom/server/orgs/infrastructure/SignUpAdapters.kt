// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.orgs.infrastructure

import run.ostrom.server.identity.domain.SignUpInvitations
import run.ostrom.server.identity.domain.SignUpPolicy
import run.ostrom.server.orgs.domain.InvitationTokens
import run.ostrom.server.orgs.domain.InvitationsRepository
import run.ostrom.server.orgs.domain.OrgCreationGate
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * [SignUpInvitations] on the organizations' invitations: a live one (e-mailed or a link) lets its person get an
 * account on an invite-only instance, before accepting it.
 *
 * @param invitations The invitations.
 * @param tokens Hashes a token as it is stored.
 * @param clock The clock.
 */
class OrgSignUpInvitations(
    private val invitations: InvitationsRepository,
    private val tokens: InvitationTokens,
    private val clock: Clock,
) : SignUpInvitations {
    override suspend fun isLive(token: String): Boolean =
        invitations.findLiveByTokenHash(tokens.hash(token), clock.now()) != null
}

/**
 * [OrgCreationGate] on the identity module's sign-up policy.
 *
 * @param signUp The policy.
 */
class SignUpOrgCreationGate(private val signUp: SignUpPolicy) : OrgCreationGate {
    override suspend fun opensTo(userId: Uuid): Boolean = signUp.mayCreateOrgs(userId)
}
