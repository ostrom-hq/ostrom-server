package dev.ostrom.server.secrets.presentation

import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.domain.CreateSecretPayload
import dev.ostrom.server.secrets.domain.CreateSecretUseCase
import dev.ostrom.server.secrets.domain.DeleteSecretUseCase
import dev.ostrom.server.secrets.domain.FindSecretUseCase
import dev.ostrom.server.secrets.domain.InvalidSecretException
import dev.ostrom.server.secrets.domain.ListSecretsUseCase
import dev.ostrom.server.secrets.domain.RotateSecretUseCase
import dev.ostrom.server.secrets.domain.Secret
import dev.ostrom.server.secrets.domain.SecretValue
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * What the secrets routes need.
 *
 * @property callers Resolves the member of each call.
 * @property createSecret Creates.
 * @property listSecrets Lists.
 * @property rotateSecret Rotates.
 * @property deleteSecret Deletes.
 * @property findSecret Finds by name.
 */
data class SecretsRoutesDependencies(
    val callers: CallerResolver,
    val createSecret: CreateSecretUseCase,
    val listSecrets: ListSecretsUseCase,
    val rotateSecret: RotateSecretUseCase,
    val deleteSecret: DeleteSecretUseCase,
    val findSecret: FindSecretUseCase,
)

/**
 * The secrets routes: authenticated, in the org of the caller's token; values go in, never out.
 *
 * @param dependencies The use cases.
 */
fun Route.secretsRoutes(dependencies: SecretsRoutesDependencies) = with(dependencies) {
    authenticated {
        get<SecretsApi> { params ->
            val caller = call.requireCaller(callers, null)
            val limit = params.limit.coerceIn(1, 200)
            val page = listSecrets(caller, params.after, limit)
            call.respond(SecretsPageResponse(page.map { it.toSecretResponse() }, page.takeIf { it.size == limit }?.last()?.name))
        }
        post<SecretsApi> {
            val caller = call.requireCaller(callers, null)
            val request = call.receive<SecretWriteRequest>()
            val secret = createSecret(caller, CreateSecretPayload(request.name, SecretValue(request.value), personalScope(request.scope)))
            call.respond(HttpStatusCode.Created, secret.toSecretResponse())
        }
        put<SecretsApi.Name> { params ->
            val caller = call.requireCaller(callers, null)
            val request = call.receive<SecretWriteRequest>()
            if (request.name != params.name) throw InvalidSecretException("The name of the body differs from the name of the path.")
            val secret = findSecret(caller, params.name, personalScope(request.scope ?: params.scope))
            call.respond(rotateSecret(caller, secret.id, SecretValue(request.value)).toSecretResponse())
        }
        delete<SecretsApi.Name> { params ->
            val caller = call.requireCaller(callers, null)
            deleteSecret(caller, findSecret(caller, params.name, personalScope(params.scope)).id)
            call.respond(HttpStatusCode.NoContent)
        }
    }
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
