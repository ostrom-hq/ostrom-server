// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway.domain

import dev.ostrom.server.secrets.domain.Caller
import kotlin.uuid.Uuid

/** Reads the org's gateway settings (admins). */
interface GetGatewaySettingsUseCase {
    /** The settings. */
    suspend operator fun invoke(caller: Caller): GatewaySettings
}

/** Replaces the org's gateway settings (admins). */
interface PutGatewaySettingsUseCase {
    /** Validates and stores them. */
    suspend operator fun invoke(caller: Caller, settings: GatewaySettings): GatewaySettings
}

/** Reads the org's model policy (any member: agents read which models they may use). */
interface GetModelPolicyUseCase {
    /** The policy. */
    suspend operator fun invoke(caller: Caller): ModelPolicy
}

/** Replaces the org's model policy (admins). */
interface PutModelPolicyUseCase {
    /** Validates and stores it. */
    suspend operator fun invoke(caller: Caller, policy: ModelPolicy): ModelPolicy
}

/** [GetGatewaySettingsUseCase] on the repository. */
class GetGatewaySettingsUseCaseImpl(private val repository: GatewaySettingsRepository) : GetGatewaySettingsUseCase {
    override suspend fun invoke(caller: Caller): GatewaySettings {
        caller.requireAdmin()
        return repository.get(caller.orgId)
    }
}

/** [PutGatewaySettingsUseCase] on the repository. */
class PutGatewaySettingsUseCaseImpl(private val repository: GatewaySettingsRepository) : PutGatewaySettingsUseCase {
    override suspend fun invoke(caller: Caller, settings: GatewaySettings): GatewaySettings {
        caller.requireAdmin()
        val names = listOfNotNull(settings.anthropicKeySecret, settings.openaiKeySecret, settings.openrouterKeySecret)
        if (names.any { !GatewayRules.SECRET_NAME.matches(it) }) {
            throw InvalidGatewaySettingsException("A key is named by the org secret holding it.")
        }
        repository.put(caller.orgId, settings)
        return settings
    }
}

/** [GetModelPolicyUseCase] on the repository. */
class GetModelPolicyUseCaseImpl(private val repository: GatewaySettingsRepository) : GetModelPolicyUseCase {
    override suspend fun invoke(caller: Caller): ModelPolicy = repository.policy(caller.orgId)
}

/** [PutModelPolicyUseCase] on the repository. */
class PutModelPolicyUseCaseImpl(private val repository: GatewaySettingsRepository) : PutModelPolicyUseCase {
    override suspend fun invoke(caller: Caller, policy: ModelPolicy): ModelPolicy {
        caller.requireAdmin()
        GatewayRules.requirePolicy(policy)
        repository.putPolicy(caller.orgId, policy)
        return policy
    }
}

/** Validation of the gateway's settings. */
object GatewayRules {
    /** Model lists per policy. */
    const val MAX_MODELS = 100

    /** Members with their own list. */
    const val MAX_MEMBERS = 1000

    /** A model name or prefix. */
    val MODEL = Regex("[A-Za-z0-9._:/-]{1,${RequestBody.MAX_MODEL}}")

    /** A secret name, as the secrets module accepts it. */
    val SECRET_NAME = Regex("[A-Za-z0-9_.-]{1,128}")

    /** Refuses a malformed policy. */
    fun requirePolicy(policy: ModelPolicy) {
        val problem = when {
            policy.byMember.size > MAX_MEMBERS -> "At most $MAX_MEMBERS members with their own list."
            policy.byMember.keys.any { Uuid.parseOrNull(it) == null } -> "byMember is keyed by member id."
            else -> (policy.byMember.values + listOf(policy.allowed, listOfNotNull(policy.default)))
                .firstNotNullOfOrNull(::modelsProblem)
        }
        if (problem != null) throw InvalidGatewaySettingsException(problem)
    }

    private fun modelsProblem(models: List<String>): String? = when {
        models.size > MAX_MODELS -> "At most $MAX_MODELS models."
        models.any { !MODEL.matches(it) } ->
            "Models are names or prefixes of letters, digits, '.', '-', '_', ':' or '/'."
        else -> null
    }
}
