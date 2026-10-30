package dev.ostrom.server.secrets.presentation

import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.domain.CreateSecretUseCase
import dev.ostrom.server.secrets.domain.DeleteSecretUseCase
import dev.ostrom.server.secrets.domain.FindSecretUseCase
import dev.ostrom.server.secrets.domain.InvalidSecretException
import dev.ostrom.server.secrets.domain.ListSecretsUseCase
import dev.ostrom.server.secrets.domain.RotateSecretUseCase
import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/**
 * `/api/v1/secrets`: the secrets of the caller's org (from the token), names only (contract `listSecrets`,
 * `createSecret`).
 *
 * @property after Keyset cursor: names after this one.
 * @property limit Page size (1 to 200, default 50).
 */
@Serializable
@Resource("/api/v1/secrets")
class SecretsApi(val after: String? = null, val limit: Int = 50) {
    /**
     * `/api/v1/secrets/{name}`: one secret (contract `rotateSecret`, `deleteSecret`).
     *
     * @property parent The collection.
     * @property name The secret's name.
     * @property scope `org` (default) or `personal`, for a delete.
     */
    @Serializable
    @Resource("{name}")
    class Name(val parent: SecretsApi = SecretsApi(), val name: String, val scope: String? = null)
}

/**
 * Creates or rotates a secret (contract `SecretWrite`).
 *
 * @property name The name, unique per owner.
 * @property value The value; never returned.
 * @property scope `org` (default; admins) or `personal` (the caller's own; any member).
 */
@Serializable
data class SecretWriteRequest(val name: String, val value: String, val scope: String? = null)

/**
 * A secret as clients see it, never its value (contract `Secret`).
 *
 * @property name The name.
 * @property scope `org` or `personal`.
 * @property createdAt Creation time (ISO 8601).
 * @property updatedAt Last change of its value (ISO 8601).
 * @property updatedBy The member who last wrote it.
 * @property version Bumped at each rotation.
 * @property owner The member a personal secret belongs to.
 */
@Serializable
data class SecretResponse(
    val name: String,
    val scope: String,
    val createdAt: String,
    val updatedAt: String,
    val updatedBy: String,
    val version: Int,
    val owner: String? = null,
)

/**
 * A page of secrets.
 *
 * @property items The secrets, by name.
 * @property next The cursor of the next page, absent on the last one.
 */
@Serializable
data class SecretsPageResponse(val items: List<SecretResponse>, val next: String? = null)

/** The `scope` of the API: true for `personal`, false for `org` or absent. */
fun personalScope(scope: String?): Boolean = when (scope) {
    null, "org" -> false
    "personal" -> true
    else -> throw InvalidSecretException("The scope is org or personal.")
}

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
