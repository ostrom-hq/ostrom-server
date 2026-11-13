// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler

import run.ostrom.server.kernel.auth.OrgRole

/** The role a test person holds in its organization, read from its id: anyone not named after a lesser role owns it. */
internal fun roleOf(id: String): OrgRole = when (id) {
    "member" -> OrgRole.MEMBER
    "approver" -> OrgRole.APPROVER
    "admin" -> OrgRole.ADMIN
    else -> OrgRole.OWNER
}
