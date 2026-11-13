// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.domain

import run.ostrom.server.secrets.domain.Caller
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

/** Reads the calls waiting in the org's admission queue (any member): counts only, no call or member named. */
interface GetInferenceQueueUseCase {
    /** The org's waiting calls and the caller's. */
    suspend operator fun invoke(caller: Caller): QueueCounts
}

/** [GetInferenceQueueUseCase] on the queue. */
class GetInferenceQueueUseCaseImpl(private val queue: AdmissionQueueRepository) : GetInferenceQueueUseCase {
    override suspend fun invoke(caller: Caller): QueueCounts = queue.counts(caller.orgId, caller.memberId)
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
        val limits = settings.memberConcurrency
        if (limits.size > GatewayRules.MAX_MEMBERS ||
            limits.any { (member, limit) -> Uuid.parseOrNull(member) == null || limit !in GatewayRules.CONCURRENCY }
        ) {
            throw InvalidGatewaySettingsException(
                "A member's concurrency limit is 1 to ${GatewayRules.CONCURRENCY.last}, by member id, " +
                    "for at most ${GatewayRules.MAX_MEMBERS} members.",
            )
        }
        // One spelling of each member id, the one the gateway looks up.
        val canonical = settings.copy(memberConcurrency = limits.mapKeys { Uuid.parse(it.key).toString() })
        repository.put(caller.orgId, canonical)
        return canonical
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
        val canonical = GatewayRules.requirePolicy(policy)
        repository.putPolicy(caller.orgId, canonical)
        return canonical
    }
}

/** Validation of the gateway's settings. */
object GatewayRules {
    /** Model lists per policy. */
    const val MAX_MODELS = 100

    /** Members with their own list, or their own concurrency limit. */
    const val MAX_MEMBERS = 1000

    /** A member's concurrency limit (TeamCode's `maxConcurrent`, at most 32). */
    val CONCURRENCY = 1..32

    /** A model name or prefix. */
    val MODEL = Regex("[A-Za-z0-9._:/-]{1,${RequestBody.MAX_MODEL}}")

    /** A secret name, as the secrets module accepts it. */
    val SECRET_NAME = Regex("[A-Za-z0-9_.-]{1,128}")

    /**
     * Refuses a malformed policy.
     *
     * @return The policy with its member ids written as [ModelPolicy.listFor] looks them up (lowercase, dashed).
     */
    fun requirePolicy(policy: ModelPolicy): ModelPolicy {
        val ids = policy.byMember.keys.map { Uuid.parseOrNull(it)?.toString() }
        val problem = when {
            policy.byMember.size > MAX_MEMBERS -> "At most $MAX_MEMBERS members with their own list."
            null in ids -> "byMember is keyed by member id."
            ids.toSet().size < ids.size -> "byMember names a member twice."
            // An empty list allows every model: as a member's own list it would lift the org's restriction.
            policy.byMember.values.any { it.isEmpty() } -> "A member's own list names at least one model."
            else -> (policy.byMember.values + listOf(policy.allowed, listOfNotNull(policy.default)))
                .firstNotNullOfOrNull(::modelsProblem)
        }
        if (problem != null) throw InvalidGatewaySettingsException(problem)
        return policy.copy(byMember = policy.byMember.mapKeys { Uuid.parse(it.key).toString() })
    }

    private fun modelsProblem(models: List<String>): String? = when {
        models.size > MAX_MODELS -> "At most $MAX_MODELS models."
        models.any { !MODEL.matches(it) } ->
            "Models are names or prefixes of letters, digits, '.', '-', '_', ':' or '/'."
        else -> null
    }
}
