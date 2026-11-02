// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler

import dev.ostrom.server.kernel.auth.OrgRole

/** The role a test person holds in its organization, read from its id: anyone not named after a lesser role owns it. */
internal fun roleOf(id: String): OrgRole = when (id) {
    "member" -> OrgRole.MEMBER
    "approver" -> OrgRole.APPROVER
    "admin" -> OrgRole.ADMIN
    else -> OrgRole.OWNER
}
