package dev.ostrom.server.instance.presentation

import dev.ostrom.server.instance.domain.GetCurrentSigningKeyUseCase
import dev.ostrom.server.instance.domain.GetOrCreateInstanceUseCase
import dev.ostrom.server.instance.domain.Instance
import dev.ostrom.server.instance.domain.ListSigningKeysUseCase
import dev.ostrom.server.instance.domain.SigningKey
import dev.ostrom.server.kernel.http.SERVER_VERSION
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * What the instance routes need.
 *
 * @property getOrCreateInstance Reads the instance.
 * @property getCurrentSigningKey Reads the current key (recorded at start).
 * @property listSigningKeys Lists the keys.
 */
data class InstanceRoutesDependencies(
    val getOrCreateInstance: GetOrCreateInstanceUseCase,
    val getCurrentSigningKey: GetCurrentSigningKeyUseCase,
    val listSigningKeys: ListSigningKeysUseCase,
)

/**
 * The public routes of the instance: unauthenticated, since clients verify signatures before they log in.
 *
 * @param dependencies The use cases.
 */
fun Route.instanceRoutes(dependencies: InstanceRoutesDependencies) = with(dependencies) {
    get<InstanceApi> {
        call.respond(getOrCreateInstance().toInstanceResponse(getCurrentSigningKey()))
    }
    get<InstanceApi.Keys> {
        call.respond(SigningKeysResponse(listSigningKeys().map { it.toSigningKeyResponse() }))
    }
}

/** Maps the instance and its current key to the API. */
fun Instance.toInstanceResponse(key: SigningKey) = InstanceResponse(
    id = id.toString(),
    version = SERVER_VERSION,
    createdAt = createdAt.toString(),
    signingKey = key.toSigningKeyResponse(),
)

/** Maps a key to the API. */
fun SigningKey.toSigningKeyResponse() = SigningKeyResponse(
    keyId = keyId,
    algorithm = algorithm,
    publicKeyPem = publicKeyPem,
    firstSeenAt = firstSeenAt.toString(),
)
