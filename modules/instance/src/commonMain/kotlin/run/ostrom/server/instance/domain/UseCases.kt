// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.instance.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.signing.InstanceSigner
import kotlin.io.encoding.Base64
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

/** The key the instance signed with before the current one, configured for a rotation; null when none is. */
fun interface PreviousSigningKey {
    /**
     * @return The previous key, or null when no rotation is configured.
     */
    suspend fun signer(): InstanceSigner?
}

/**
 * [RegisterCurrentSigningKeyUseCase] on the repository. When the previous key is configured, it hands the current key
 * over ([KeyRotation]); only a key this instance signed with does, so a foreign key never vouches for the new one.
 *
 * @param repository The storage.
 * @param signer The instance's key.
 * @param clock The first-seen date's clock.
 * @param previous The previous key, for a rotation.
 * @param logger Says why a configured previous key hands nothing over.
 */
class RegisterCurrentSigningKeyUseCaseImpl(
    private val repository: InstanceRepository,
    private val signer: InstanceSigner,
    private val clock: Clock,
    private val previous: PreviousSigningKey,
    private val logger: Logger,
) : RegisterCurrentSigningKeyUseCase {
    override suspend fun invoke(): SigningKey {
        val rotation = previous.signer()?.let { rotationBy(it) }
        val key = SigningKey(signer.keyId, signer.algorithm, signer.publicKeyPem, clock.now(), rotation)
        repository.registerKey(key)
        return repository.listKeys().first { it.keyId == key.keyId }
    }

    private suspend fun rotationBy(previous: InstanceSigner): KeyRotation? {
        val refusal = when {
            previous.keyId == signer.keyId -> "the previous key is the current one"
            repository.listKeys().none { it.keyId == previous.keyId } -> "this instance never signed with it"
            else -> null
        }
        if (refusal != null) {
            logger.warn("The previous signing key hands nothing over: $refusal.", mapOf("kid" to previous.keyId))
        }
        return if (refusal == null) {
            KeyRotation(previous.keyId, Base64.encode(previous.sign(KeyRotation.message(signer.publicKeyPem))))
        } else {
            null
        }
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

/** Returns the key the instance signs with now, as recorded at start, without writing anything. */
interface GetCurrentSigningKeyUseCase {
    /**
     * Reads the current key.
     *
     * @return The current key and its first-seen date.
     *
     * @throws OstromException With [ErrorKind.UNAVAILABLE] if the start has not recorded it (it always does).
     */
    suspend operator fun invoke(): SigningKey
}

/**
 * [GetCurrentSigningKeyUseCase] on the repository.
 *
 * @param repository The storage.
 * @param signer The instance's key.
 */
class GetCurrentSigningKeyUseCaseImpl(
    private val repository: InstanceRepository,
    private val signer: InstanceSigner,
) : GetCurrentSigningKeyUseCase {
    override suspend fun invoke(): SigningKey = repository.listKeys().firstOrNull { it.keyId == signer.keyId }
        ?: throw OstromException(ErrorKind.UNAVAILABLE, "The signing key is not registered yet.")
}
