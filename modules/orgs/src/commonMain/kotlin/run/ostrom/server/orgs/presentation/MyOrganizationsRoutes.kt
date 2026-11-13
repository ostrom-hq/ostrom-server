// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.orgs.presentation

import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requireScope
import run.ostrom.server.orgs.domain.Membership
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * `GET /api/v1/me/organizations`: the caller's organizations, to choose the one `Ostrom-Org` names. It resolves no
 * organization, so it never needs the header and never answers 409 `org_required`.
 *
 * @param dependencies The use cases.
 */
fun Route.myOrganizationsRoutes(dependencies: OrgsRoutesDependencies) =
    with(dependencies) {
        authenticated {
            get<MyOrganizationsApi> {
                val userId = call.requireScope(ORGS_READ).userId()
                call.respond(MyOrganizationsResponse(orgs.listMine(userId).map { it.toMyOrganizationResponse() }))
            }
        }
    }

/** Maps one of the caller's organizations; the avatar is the linked GitHub organization's. */
internal fun Membership.toMyOrganizationResponse() =
    MyOrganizationResponse(
        org.id.toString(),
        org.slug,
        org.name,
        org.avatarUrl,
        member.role.key(),
    )
