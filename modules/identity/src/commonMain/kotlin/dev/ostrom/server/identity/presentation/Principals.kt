// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.presentation

import dev.ostrom.server.identity.domain.SessionRequiredException
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import kotlin.uuid.Uuid

/**
 * The user id of a user principal.
 *
 * @return The id.
 *
 * @throws SessionRequiredException When the principal is not a user (an agent).
 */
fun AuthenticatedPrincipal.userId(): Uuid {
    if (kind != PrincipalKind.USER) throw SessionRequiredException()
    return Uuid.parseOrNull(subjectId) ?: throw SessionRequiredException()
}

/**
 * The credential a user principal signed in with.
 *
 * @return Its id.
 *
 * @throws SessionRequiredException When the principal carries none (a service).
 */
internal fun AuthenticatedPrincipal.credentialUuid(): Uuid =
    credentialId?.let(Uuid::parseOrNull) ?: throw SessionRequiredException()

/**
 * The member and organization the request names in `Ostrom-Org`, for `/me`.
 *
 * @param principal The caller.
 * @return Both, or null when the header names no organization the caller is a member of.
 */
internal suspend fun PrincipalOrgResolver.currentOrganization(
    principal: AuthenticatedPrincipal,
): Pair<MeMemberResponse, MeOrganizationResponse>? {
    val member = resolve(principal, null)
    val org = member?.let { profile(it.orgId) }
    return if (member == null || org == null) {
        null
    } else {
        MeMemberResponse(member.memberId.toString(), member.role.name.lowercase()) to
            MeOrganizationResponse(org.id.toString(), org.slug, org.name)
    }
}
