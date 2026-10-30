package dev.ostrom.server.runners.presentation

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.runners.domain.Caller
import dev.ostrom.server.runners.domain.MissingScopeException
import dev.ostrom.server.runners.domain.NoOrganizationException
import kotlin.uuid.Uuid

/** The scope that reads cloud sessions. */
const val CLOUD_READ = "cloud:read"

/** The scope that creates, pauses, resumes, deletes and attaches to cloud sessions. */
const val CLOUD_WRITE = "cloud:write"

private const val ORG_SCOPE_PREFIX = "org:"

/**
 * The caller of a cloud route. Until the identity module puts the organization on the principal, it is read from an
 * `org:<uuid>` scope (decision of 30/10/2026, `.coord/QUESTIONS.md`); a principal without it is refused.
 *
 * @param scope The scope the action needs; a token with an explicit scope list must hold it ([CLOUD_WRITE] implies
 *   [CLOUD_READ]).
 *
 * @return The caller.
 *
 * @throws NoOrganizationException When the principal names no organization.
 * @throws MissingScopeException When the token's scopes do not allow the action.
 */
fun AuthenticatedPrincipal.toCaller(scope: String): Caller {
    val orgId = scopes.firstNotNullOfOrNull { granted ->
        granted.takeIf { it.startsWith(ORG_SCOPE_PREFIX) }?.let { Uuid.parseOrNull(it.removePrefix(ORG_SCOPE_PREFIX)) }
    } ?: throw NoOrganizationException()
    val actionScopes = scopes.filterNot { it.startsWith(ORG_SCOPE_PREFIX) }
    val allowed = actionScopes.isEmpty() || scope in actionScopes || (scope == CLOUD_READ && CLOUD_WRITE in actionScopes)
    if (!allowed) throw MissingScopeException(scope)
    return Caller(orgId, subjectId)
}
