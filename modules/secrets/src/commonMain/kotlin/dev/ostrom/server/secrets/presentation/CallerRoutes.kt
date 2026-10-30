package dev.ostrom.server.secrets.presentation

import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.domain.OrgNotFoundException
import io.ktor.server.application.ApplicationCall
import kotlin.uuid.Uuid

/**
 * The member an authenticated call acts for.
 *
 * @param resolver Resolves principals.
 * @param orgId The org id of the URL, or null on agent routes bound to the token's org.
 *
 * @return The caller.
 *
 * @throws OrgNotFoundException When the id is malformed or the principal is not a member of the org.
 */
suspend fun ApplicationCall.requireCaller(resolver: CallerResolver, orgId: String?): Caller {
    val org = orgId?.let { Uuid.parseOrNull(it) ?: throw OrgNotFoundException() }
    return resolver.resolve(requirePrincipal(), org) ?: throw OrgNotFoundException()
}

/**
 * Parses an id of the URL.
 *
 * @param notFound The exception for a malformed id, the same as for an unknown one.
 *
 * @return The id.
 */
fun String.toIdOr(notFound: () -> Exception): Uuid = Uuid.parseOrNull(this) ?: throw notFound()
