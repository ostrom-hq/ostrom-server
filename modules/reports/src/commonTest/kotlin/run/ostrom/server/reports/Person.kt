// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.reports

import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import kotlin.uuid.Uuid

/** A person of the seed: their user, their membership, their role. */
data class Person(val login: String, val org: Uuid, val role: OrgRole) {
    val userId: Uuid = Uuid.random()
    val memberId: Uuid = Uuid.random()
    val principal: OrgPrincipal get() = OrgPrincipal(org, memberId, role)
}
