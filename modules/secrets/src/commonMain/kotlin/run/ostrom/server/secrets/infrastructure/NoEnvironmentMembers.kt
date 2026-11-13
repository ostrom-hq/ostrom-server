// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.secrets.infrastructure

import run.ostrom.server.kernel.auth.MemberDirectory
import kotlin.uuid.Uuid

/** The members when no module binds [MemberDirectory]: nobody, so no environment value is ever served or assigned. */
internal object NoEnvironmentMembers : MemberDirectory {
    override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean = false
}
