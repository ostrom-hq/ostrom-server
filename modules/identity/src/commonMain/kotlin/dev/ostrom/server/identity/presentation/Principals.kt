// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.presentation

import dev.ostrom.server.identity.domain.SessionRequiredException
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
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
