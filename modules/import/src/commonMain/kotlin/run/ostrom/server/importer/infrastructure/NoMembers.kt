// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.importer.infrastructure

import run.ostrom.server.kernel.auth.MemberDirectory
import kotlin.uuid.Uuid

/**
 * The member directory until the orgs module binds one: no one is a member, so a member map is refused rather than
 * trusted. An import without a member map still runs.
 */
internal object NoMembers : MemberDirectory {
    override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean = false
}
