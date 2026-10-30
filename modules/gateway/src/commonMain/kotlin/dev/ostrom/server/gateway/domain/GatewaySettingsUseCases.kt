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
        listOfNotNull(settings.anthropicKeySecret, settings.openaiKeySecret, settings.openrouterKeySecret).forEach {
            if (!GatewayRules.SECRET_NAME.matches(it)) throw InvalidGatewaySettingsException("A key is named by the org secret holding it.")
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
        GatewayRules.requireModels(policy.allowed)
        if (policy.byMember.size > GatewayRules.MAX_MEMBERS) throw InvalidGatewaySettingsException("At most ${GatewayRules.MAX_MEMBERS} members with their own list.")
        policy.byMember.forEach { (member, models) ->
            if (Uuid.parseOrNull(member) == null) throw InvalidGatewaySettingsException("byMember is keyed by member id.")
            GatewayRules.requireModels(models)
        }
        policy.default?.let { GatewayRules.requireModels(listOf(it)) }
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

    /** The longest model name. */
    const val MAX_MODEL = 200

    /** A model name or prefix. */
    val MODEL = Regex("[A-Za-z0-9._:/-]+")

    /** A secret name, as the secrets module accepts it. */
    val SECRET_NAME = Regex("[A-Za-z0-9_.-]{1,128}")

    /** Refuses a malformed list. */
    fun requireModels(models: List<String>) {
        if (models.size > MAX_MODELS) throw InvalidGatewaySettingsException("At most $MAX_MODELS models.")
        if (models.any { it.isEmpty() || it.length > MAX_MODEL || !MODEL.matches(it) }) {
            throw InvalidGatewaySettingsException("Models are names or prefixes of letters, digits, '.', '-', '_', ':' or '/'.")
        }
    }
}
