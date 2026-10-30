package dev.ostrom.server.instance.domain

import dev.ostrom.server.kernel.signing.InstanceSigner
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Returns the instance, creating it with a new id the first time. */
interface GetOrCreateInstanceUseCase {
    /**
     * Returns the instance.
     *
     * @return The instance, the same on every call and every start.
     */
    suspend operator fun invoke(): Instance
}

/**
 * [GetOrCreateInstanceUseCase] on the repository.
 *
 * @param repository The storage.
 * @param clock The creation date's clock.
 */
class GetOrCreateInstanceUseCaseImpl(
    private val repository: InstanceRepository,
    private val clock: Clock,
) : GetOrCreateInstanceUseCase {
    override suspend fun invoke(): Instance =
        repository.get() ?: repository.createIfAbsent(Instance(Uuid.random(), clock.now()))
}

/** Records the key the instance signs with now, so that it stays listed after a key change. */
interface RegisterCurrentSigningKeyUseCase {
    /**
     * Records the current key; calling it again changes nothing.
     *
     * @return The current key.
     */
    suspend operator fun invoke(): SigningKey
}

/**
 * [RegisterCurrentSigningKeyUseCase] on the repository.
 *
 * @param repository The storage.
 * @param signer The instance's key.
 * @param clock The first-seen date's clock.
 */
class RegisterCurrentSigningKeyUseCaseImpl(
    private val repository: InstanceRepository,
    private val signer: InstanceSigner,
    private val clock: Clock,
) : RegisterCurrentSigningKeyUseCase {
    override suspend fun invoke(): SigningKey {
        val key = SigningKey(signer.keyId, signer.algorithm, signer.publicKeyPem, clock.now())
        repository.registerKey(key)
        return repository.listKeys().first { it.keyId == key.keyId }
    }
}

/** Lists every signing key the instance has used. */
interface ListSigningKeysUseCase {
    /**
     * Lists the keys.
     *
     * @return The keys, oldest first.
     */
    suspend operator fun invoke(): List<SigningKey>
}

/**
 * [ListSigningKeysUseCase] on the repository.
 *
 * @param repository The storage.
 */
class ListSigningKeysUseCaseImpl(
    private val repository: InstanceRepository,
) : ListSigningKeysUseCase {
    override suspend fun invoke(): List<SigningKey> = repository.listKeys()
}
