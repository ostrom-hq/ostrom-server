// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.secrets.presentation

import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requireRecentSignIn
import run.ostrom.server.secrets.domain.CreateSecretPayload
import run.ostrom.server.secrets.domain.InvalidSecretException
import run.ostrom.server.secrets.domain.RelayScopes
import run.ostrom.server.secrets.domain.Secret
import run.ostrom.server.secrets.domain.SecretCursor
import run.ostrom.server.secrets.domain.SecretRules
import run.ostrom.server.secrets.domain.SecretValue
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid

/**
 * The secrets routes: authenticated, in the org of the caller's token; values go in, never out.
 *
 * @param dependencies The use cases.
 */
fun Route.secretsRoutes(dependencies: SecretsRoutesDependencies) = with(dependencies) {
    authenticated {
        get<SecretsApi> { params ->
            val caller = call.requireCaller(callers, RelayScopes.SECRETS_READ)
            val limit = params.limit.coerceIn(1, SecretRules.MAX_PAGE)
            val page = listSecrets(caller, params.after?.toSecretCursor(), limit)
            val next = if (page.size == limit) page.last().toCursor() else null
            call.respond(SecretsPageResponse(page.map { it.toSecretResponse() }, next))
        }
        post<SecretsApi> {
            val caller = call.requireCaller(callers, RelayScopes.SECRETS_WRITE)
            call.requireRecentSignIn("change a secret")
            val request = call.receive<SecretWriteRequest>()
            val payload = CreateSecretPayload(request.name, SecretValue(request.value), personalScope(request.scope))
            call.respond(HttpStatusCode.Created, createSecret(caller, payload).toSecretResponse())
        }
        put<SecretsApi.Name> { params ->
            val caller = call.requireCaller(callers, RelayScopes.SECRETS_WRITE)
            call.requireRecentSignIn("change a secret")
            val request = call.receive<SecretWriteRequest>()
            if (request.name != params.name) {
                throw InvalidSecretException("The name of the body differs from the name of the path.")
            }
            val secret = findSecret(caller, params.name, personalScope(request.scope ?: params.scope))
            call.respond(rotateSecret(caller, secret.id, SecretValue(request.value)).toSecretResponse())
        }
        delete<SecretsApi.Name> { params ->
            val caller = call.requireCaller(callers, RelayScopes.SECRETS_WRITE)
            call.requireRecentSignIn("change a secret")
            deleteSecret(caller, findSecret(caller, params.name, personalScope(params.scope)).id)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** The opaque cursor of the page after [this]: its name, `/` (never in a name), its id. */
fun Secret.toCursor(): String = "$name/$id"

/**
 * Reads a cursor of [toCursor].
 *
 * @throws InvalidSecretException When it is not one.
 */
fun String.toSecretCursor(): SecretCursor {
    val id = Uuid.parseOrNull(substringAfterLast('/', missingDelimiterValue = ""))
        ?: throw InvalidSecretException("The cursor is not one this server returned.")
    return SecretCursor(substringBeforeLast('/'), id)
}

/** Maps a secret to the API, without its value. */
fun Secret.toSecretResponse() = SecretResponse(
    name = name,
    scope = if (ownerMemberId == null) "org" else "personal",
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    updatedBy = updatedBy.toString(),
    version = version,
    owner = ownerMemberId?.toString(),
)
