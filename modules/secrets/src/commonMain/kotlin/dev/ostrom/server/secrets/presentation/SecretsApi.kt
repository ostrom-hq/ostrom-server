package dev.ostrom.server.secrets.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/**
 * `/v1/orgs/{orgId}/secrets`: the org's secrets, names only. To move to ostrom-core with the API contract.
 *
 * @property orgId The org.
 * @property after Keyset cursor: names after this one.
 * @property limit Page size (1 to 200, default 50).
 */
@Serializable
@Resource("/v1/orgs/{orgId}/secrets")
class SecretsApi(val orgId: String, val after: String? = null, val limit: Int = 50) {
    /**
     * `/v1/orgs/{orgId}/secrets/{id}`: one secret.
     *
     * @property parent The collection.
     * @property id The secret.
     */
    @Serializable
    @Resource("{id}")
    class Id(val parent: SecretsApi, val id: String)
}

/**
 * Creates a secret.
 *
 * @property name The name, unique per owner.
 * @property value The value; never returned.
 * @property personal True for a secret of the caller only (any member); false for an org secret (admins).
 */
@Serializable
data class CreateSecretRequest(val name: String, val value: String, val personal: Boolean = false)

/**
 * Replaces a secret's value.
 *
 * @property value The new value.
 */
@Serializable
data class RotateSecretRequest(val value: String)

/**
 * A secret as clients see it: never its value.
 *
 * @property id The id.
 * @property name The name.
 * @property personal Whether it belongs to one member.
 * @property ownerMemberId The member it belongs to, when personal.
 * @property version The version, bumped at each rotation.
 * @property createdAt Creation time (ISO 8601).
 * @property updatedAt Last change of its value (ISO 8601).
 */
@Serializable
data class SecretResponse(
    val id: String,
    val name: String,
    val personal: Boolean,
    val ownerMemberId: String? = null,
    val version: Int,
    val createdAt: String,
    val updatedAt: String,
)

/**
 * A page of secrets.
 *
 * @property secrets The secrets, by name.
 * @property next The cursor of the next page, or null on the last one.
 */
@Serializable
data class SecretsPageResponse(val secrets: List<SecretResponse>, val next: String? = null)
