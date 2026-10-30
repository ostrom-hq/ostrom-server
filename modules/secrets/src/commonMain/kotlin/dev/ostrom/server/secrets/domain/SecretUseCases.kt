package dev.ostrom.server.secrets.domain

import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * What creating a secret needs.
 *
 * @property name The name.
 * @property value The value.
 * @property personal Whether it belongs to the caller only (any member) rather than to the org (admins).
 */
data class CreateSecretPayload(val name: String, val value: SecretValue, val personal: Boolean)

/** Creates a secret. */
interface CreateSecretUseCase {
    /**
     * Creates it.
     *
     * @param caller Who creates it: an admin for an org secret, any member for a personal one.
     * @param payload The secret.
     *
     * @return Its metadata.
     *
     * @throws AdminRequiredException When a member creates an org secret.
     * @throws InvalidSecretException When the name or value is malformed.
     * @throws SecretNameTakenException When the name is taken for that owner.
     */
    suspend operator fun invoke(caller: Caller, payload: CreateSecretPayload): Secret
}

/**
 * [CreateSecretUseCase] on the repository.
 *
 * @param repository The storage.
 * @param cipher The instance key.
 * @param clock The time.
 */
class CreateSecretUseCaseImpl(
    private val repository: SecretsRepository,
    private val cipher: SecretCipher,
    private val clock: Clock,
) : CreateSecretUseCase {
    override suspend fun invoke(caller: Caller, payload: CreateSecretPayload): Secret {
        if (!payload.personal) caller.requireAdmin()
        SecretRules.requireName(payload.name)
        SecretRules.requireValue(payload.value.reveal())
        val now = clock.now()
        val secret = Secret(
            id = Uuid.random(),
            orgId = caller.orgId,
            name = payload.name,
            ownerMemberId = if (payload.personal) caller.memberId else null,
            version = 1,
            createdAt = now,
            updatedAt = now,
            updatedBy = caller.memberId,
        )
        val envelope = cipher.seal(payload.value.reveal().encodeToByteArray(), secretContext(secret.orgId, secret.id))
        if (!repository.create(StoredSecret(secret, envelope))) throw SecretNameTakenException()
        return secret
    }
}

/** Lists the secrets a caller can see, names only. */
interface ListSecretsUseCase {
    /**
     * Lists them.
     *
     * @param caller The caller: admins see every secret, members the org's and their own.
     * @param afterName Keyset cursor.
     * @param limit The page size, at most [SecretRules.MAX_PAGE].
     *
     * @return The secrets, by name.
     */
    suspend operator fun invoke(caller: Caller, afterName: String?, limit: Int): List<Secret>
}

/**
 * [ListSecretsUseCase] on the repository.
 *
 * @param repository The storage.
 */
class ListSecretsUseCaseImpl(private val repository: SecretsRepository) : ListSecretsUseCase {
    override suspend fun invoke(caller: Caller, afterName: String?, limit: Int): List<Secret> =
        repository.list(
            orgId = caller.orgId,
            memberId = if (caller.role.isAdmin) null else caller.memberId,
            afterName = afterName,
            limit = limit.coerceIn(1, SecretRules.MAX_PAGE),
        )
}

/** Replaces a secret's value. */
interface RotateSecretUseCase {
    /**
     * Rotates it.
     *
     * @param caller An admin for an org secret, the owner for a personal one.
     * @param id The secret.
     * @param value The new value.
     *
     * @return The secret after the change, its version bumped.
     *
     * @throws SecretNotFoundException When the caller cannot see it.
     * @throws AdminRequiredException When a member rotates an org secret.
     */
    suspend operator fun invoke(caller: Caller, id: Uuid, value: SecretValue): Secret
}

/**
 * [RotateSecretUseCase] on the repository.
 *
 * @param repository The storage.
 * @param cipher The instance key.
 * @param clock The time.
 */
class RotateSecretUseCaseImpl(
    private val repository: SecretsRepository,
    private val cipher: SecretCipher,
    private val clock: Clock,
) : RotateSecretUseCase {
    override suspend fun invoke(caller: Caller, id: Uuid, value: SecretValue): Secret {
        val current = repository.requireWritable(caller, id)
        SecretRules.requireValue(value.reveal())
        val envelope = cipher.seal(value.reveal().encodeToByteArray(), secretContext(current.orgId, current.id))
        return repository.rotate(caller.orgId, id, envelope, clock.now(), caller.memberId) ?: throw SecretNotFoundException()
    }
}

/** Deletes a secret. */
interface DeleteSecretUseCase {
    /**
     * Deletes it.
     *
     * @param caller An admin for an org secret, the owner for a personal one.
     * @param id The secret.
     *
     * @throws SecretNotFoundException When the caller cannot see it.
     * @throws AdminRequiredException When a member deletes an org secret.
     */
    suspend operator fun invoke(caller: Caller, id: Uuid)
}

/**
 * [DeleteSecretUseCase] on the repository.
 *
 * @param repository The storage.
 */
class DeleteSecretUseCaseImpl(private val repository: SecretsRepository) : DeleteSecretUseCase {
    override suspend fun invoke(caller: Caller, id: Uuid) {
        repository.requireWritable(caller, id)
        if (!repository.delete(caller.orgId, id)) throw SecretNotFoundException()
    }
}

/**
 * Decrypts secrets for server-side injection (MCP relay, inference gateway). Never bound to a route: no client
 * ever receives a value.
 */
interface RevealSecretsUseCase {
    /**
     * Decrypts secrets of one org.
     *
     * @param orgId The org.
     * @param ids The secrets.
     *
     * @return The values by id; a missing id is absent from the map.
     *
     * @throws SecretUnreadableException When a stored envelope does not open with this instance's key.
     */
    suspend operator fun invoke(orgId: Uuid, ids: Collection<Uuid>): Map<Uuid, SecretValue>

    /**
     * Decrypts org secrets (never personal ones) by name: MCP servers and gateway settings name the secrets they use.
     *
     * @return The values by name; a missing name is absent from the map.
     *
     * @throws SecretUnreadableException When a stored envelope does not open with this instance's key.
     */
    suspend fun byName(orgId: Uuid, names: Collection<String>): Map<String, SecretValue>
}

/** Finds the id of a secret the caller can see, by name: the API addresses secrets by name. */
interface FindSecretUseCase {
    /**
     * Finds it.
     *
     * @param caller The caller.
     * @param name The name.
     * @param personal True for the caller's personal secret, false for the org secret.
     *
     * @throws SecretNotFoundException When there is none.
     */
    suspend operator fun invoke(caller: Caller, name: String, personal: Boolean): Secret
}

/** [FindSecretUseCase] on the repository. */
class FindSecretUseCaseImpl(private val repository: SecretsRepository) : FindSecretUseCase {
    override suspend fun invoke(caller: Caller, name: String, personal: Boolean): Secret =
        repository.findByName(caller.orgId, if (personal) caller.memberId else null, name) ?: throw SecretNotFoundException()
}

/**
 * [RevealSecretsUseCase] on the repository: one query for every id.
 *
 * @param repository The storage.
 * @param cipher The instance key.
 */
class RevealSecretsUseCaseImpl(
    private val repository: SecretsRepository,
    private val cipher: SecretCipher,
) : RevealSecretsUseCase {
    override suspend fun invoke(orgId: Uuid, ids: Collection<Uuid>): Map<Uuid, SecretValue> {
        if (ids.isEmpty()) return emptyMap()
        return repository.getMany(orgId, ids.distinct()).associate { stored ->
            val plaintext = cipher.open(stored.envelope, secretContext(stored.secret.orgId, stored.secret.id))
                ?: throw SecretUnreadableException()
            stored.secret.id to SecretValue(plaintext.decodeToString())
        }
    }

    override suspend fun byName(orgId: Uuid, names: Collection<String>): Map<String, SecretValue> {
        if (names.isEmpty()) return emptyMap()
        return repository.getOrgSecretsByName(orgId, names.distinct()).associate { stored ->
            val plaintext = cipher.open(stored.envelope, secretContext(stored.secret.orgId, stored.secret.id))
                ?: throw SecretUnreadableException()
            stored.secret.name to SecretValue(plaintext.decodeToString())
        }
    }
}

private suspend fun SecretsRepository.requireWritable(caller: Caller, id: Uuid): Secret {
    val secret = get(caller.orgId, id)?.secret ?: throw SecretNotFoundException()
    val owner = secret.ownerMemberId
    when {
        owner == null -> caller.requireAdmin()
        // Someone else's personal secret: admins manage it (a member who left), others do not even see it.
        owner != caller.memberId && !caller.role.isAdmin -> throw SecretNotFoundException()
    }
    return secret
}

/**
 * Translates between secret names (what the API and its clients use) and ids (what other modules store, so a
 * rotation or a rename keeps their reference). Holds no value.
 */
interface SecretDirectoryUseCase {
    /**
     * Ids of secrets by name.
     *
     * @param ownerMemberId The member for personal secrets, null for org secrets.
     *
     * @return The id of each name found.
     */
    suspend fun idsByName(orgId: Uuid, ownerMemberId: Uuid?, names: Collection<String>): Map<String, Uuid>

    /** Names of secrets by id; a missing id is absent. */
    suspend fun namesById(orgId: Uuid, ids: Collection<Uuid>): Map<Uuid, String>
}

/** [SecretDirectoryUseCase] on the repository. */
class SecretDirectoryUseCaseImpl(private val repository: SecretsRepository) : SecretDirectoryUseCase {
    override suspend fun idsByName(orgId: Uuid, ownerMemberId: Uuid?, names: Collection<String>): Map<String, Uuid> =
        names.distinct().mapNotNull { name -> repository.findByName(orgId, ownerMemberId, name)?.let { name to it.id } }.toMap()

    override suspend fun namesById(orgId: Uuid, ids: Collection<Uuid>): Map<Uuid, String> =
        if (ids.isEmpty()) emptyMap() else repository.getMany(orgId, ids.distinct()).associate { it.secret.id to it.secret.name }
}
