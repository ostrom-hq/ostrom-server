// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.integrations.github.infrastructure

import run.ostrom.server.kernel.auth.MemberDirectory
import kotlin.uuid.Uuid

/** Nobody is a member: a server built without the orgs module links no one. */
object NoGitHubLinkMembers : MemberDirectory {
    override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean = false
}
