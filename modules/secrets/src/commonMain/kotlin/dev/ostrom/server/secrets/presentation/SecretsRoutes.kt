package dev.ostrom.server.secrets.presentation

import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.domain.CreateSecretPayload
import dev.ostrom.server.secrets.domain.CreateSecretUseCase
import dev.ostrom.server.secrets.domain.DeleteSecretUseCase
import dev.ostrom.server.secrets.domain.ListSecretsUseCase
import dev.ostrom.server.secrets.domain.RotateSecretUseCase
import dev.ostrom.server.secrets.domain.Secret
import dev.ostrom.server.secrets.domain.SecretNotFoundException
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
 */
data class SecretsRoutesDependencies(
    val callers: CallerResolver,
    val createSecret: CreateSecretUseCase,
    val listSecrets: ListSecretsUseCase,
    val rotateSecret: RotateSecretUseCase,
    val deleteSecret: DeleteSecretUseCase,
)

/**
 * The secrets routes: authenticated, scoped to an org the caller belongs to; values go in, never out.
 *
 * @param dependencies The use cases.
 */
fun Route.secretsRoutes(dependencies: SecretsRoutesDependencies) = with(dependencies) {
    authenticated {
        get<SecretsApi> { params ->
            val caller = call.requireCaller(callers, params.orgId)
            val limit = params.limit.coerceIn(1, 200)
            val page = listSecrets(caller, params.after, limit)
            call.respond(SecretsPageResponse(page.map { it.toSecretResponse() }, page.takeIf { it.size == limit }?.last()?.name))
        }
        post<SecretsApi> { params ->
            val caller = call.requireCaller(callers, params.orgId)
            val request = call.receive<CreateSecretRequest>()
            val secret = createSecret(caller, CreateSecretPayload(request.name, SecretValue(request.value), request.personal))
            call.respond(HttpStatusCode.Created, secret.toSecretResponse())
        }
        put<SecretsApi.Id> { params ->
            val caller = call.requireCaller(callers, params.parent.orgId)
            val request = call.receive<RotateSecretRequest>()
            call.respond(rotateSecret(caller, params.id.toIdOr(::SecretNotFoundException), SecretValue(request.value)).toSecretResponse())
        }
        delete<SecretsApi.Id> { params ->
            val caller = call.requireCaller(callers, params.parent.orgId)
            deleteSecret(caller, params.id.toIdOr(::SecretNotFoundException))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** Maps a secret to the API, without its value. */
fun Secret.toSecretResponse() = SecretResponse(
    id = id.toString(),
    name = name,
    personal = ownerMemberId != null,
    ownerMemberId = ownerMemberId?.toString(),
    version = version,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)
