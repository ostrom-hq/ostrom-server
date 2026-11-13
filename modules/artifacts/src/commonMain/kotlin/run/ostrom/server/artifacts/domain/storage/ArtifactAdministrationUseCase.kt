// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts.domain.storage

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The organization's artifact bucket and settings (TeamCode's `ArtifactAdministrationService`): a person who owns or
 * administers the organization reads and changes them; no key does, whatever its scopes. Every member learns whether
 * they may publish from [policy].
 */
interface ArtifactAdministrationUseCase {
    /**
     * Reads the bucket, the settings and whether artifacts are in use.
     *
     * @throws ArtifactAdministratorRequiredException When the caller is not a person who administers.
     */
    suspend fun overview(caller: StorageCaller): ArtifactStorageOverview

    /**
     * Saves the bucket; artifacts are inactive until a test of the new version passes.
     *
     * @param expectedVersion The version replaced, 0 for the first save.
     *
     * @return The saved bucket.
     *
     * @throws ArtifactAdministratorRequiredException When the caller is not a person who administers.
     * @throws InvalidArtifactStorageException When a field is malformed.
     * @throws ArtifactStorageHostNotAllowedException When the endpoint's host is not allowed.
     * @throws ArtifactStorageSecretRequiredException When the endpoint or the bucket changes without a new secret.
     * @throws ArtifactStorageInUseException When the endpoint, bucket or prefix changes while artifacts exist.
     * @throws ArtifactStorageConflictException When [expectedVersion] is not the current one.
     */
    suspend fun saveStorage(caller: StorageCaller, expectedVersion: Int, input: ArtifactStorageInput): ArtifactStorage

    /**
     * Removes the bucket and turns artifacts off.
     *
     * @throws ArtifactAdministratorRequiredException When the caller is not a person who administers.
     * @throws ArtifactStorageInUseException While artifacts exist.
     * @throws ArtifactStorageMissingException When none is stored.
     */
    suspend fun deleteStorage(caller: StorageCaller)

    /**
     * Tests the connection: writes, reads back (SHA-256 compared), lists and deletes one probe object, each step
     * reported with its status and code only, within 80 seconds, one test at a time.
     *
     * @return The result.
     *
     * @throws ArtifactAdministratorRequiredException When the caller is not a person who administers.
     * @throws ArtifactStorageMissingException When none is stored.
     * @throws ArtifactStorageTestRunningException While another test runs.
     */
    suspend fun testStorage(caller: StorageCaller): StorageTestResult

    /**
     * Saves every setting.
     *
     * @return The saved settings.
     *
     * @throws ArtifactAdministratorRequiredException When the caller is not a person who administers.
     * @throws InvalidArtifactSettingsException When one is out of its range.
     * @throws ArtifactsInactiveException When turning artifacts on without a bucket, or without a passed test.
     * @throws ArtifactSettingsConflictException When their version is not the current one.
     */
    suspend fun saveSettings(caller: StorageCaller, settings: ArtifactSettings): ArtifactSettings

    /**
     * Whether the caller may publish now, for any member credential: inactive artifacts first, then publication
     * reserved to administrators, then a credential without `artifacts:write`.
     *
     * @return The policy.
     */
    suspend fun policy(caller: StorageCaller): ArtifactPolicy
}

/**
 * [ArtifactAdministrationUseCase] on the repository and the object store.
 *
 * @param repository The bucket and the settings.
 * @param objects The bucket's operations.
 * @param digest SHA-256, to compare the probe read back.
 * @param clock The time of saves and tests.
 * @param probeId The probe object's name.
 * @param deadline The whole test's bound.
 */
class ArtifactAdministrationUseCaseImpl(
    private val repository: ArtifactStorageRepository,
    private val objects: ArtifactObjectStore,
    private val digest: suspend (ByteArray) -> String,
    private val clock: Clock = Clock.System,
    private val probeId: () -> String = { Uuid.random().toString() },
    private val deadline: Duration = TEST_DEADLINE,
) : ArtifactAdministrationUseCase {
    private val testing = Mutex()

    override suspend fun overview(caller: StorageCaller): ArtifactStorageOverview {
        caller.requireAdministrator()
        return repository.overview(caller.orgId)
    }

    override suspend fun saveStorage(
        caller: StorageCaller,
        expectedVersion: Int,
        input: ArtifactStorageInput,
    ): ArtifactStorage {
        caller.requireAdministrator()
        StorageRules.validate(input)
        // One spelling of the endpoint, so a trailing slash never reads as a move to another endpoint.
        val normalized = ArtifactStorageInput(
            input.endpoint.removeSuffix("/"), input.region, input.bucket, input.prefix, input.pathStyle,
            input.accessKeyId, input.secretAccessKey, input.serverSideEncryption,
        )
        val current = repository.overview(caller.orgId)
        val stored = current.storage
        if (stored != null) {
            val moved = stored.endpoint != normalized.endpoint || stored.bucket != normalized.bucket
            if (moved && normalized.secretAccessKey == null) throw ArtifactStorageSecretRequiredException()
            if (current.inUse && (moved || stored.prefix != normalized.prefix)) throw ArtifactStorageInUseException()
        } else if (normalized.secretAccessKey == null) {
            throw ArtifactStorageSecretRequiredException()
        }
        return repository.saveStorage(caller.orgId, expectedVersion, normalized, clock.now())
            ?: throw ArtifactStorageConflictException()
    }

    override suspend fun deleteStorage(caller: StorageCaller) {
        caller.requireAdministrator()
        if (repository.overview(caller.orgId).inUse) throw ArtifactStorageInUseException()
        if (!repository.deleteStorage(caller.orgId, clock.now())) throw ArtifactStorageMissingException()
    }

    override suspend fun testStorage(caller: StorageCaller): StorageTestResult {
        caller.requireAdministrator()
        // One test at a time on this replica: a double click never runs two probe sequences side by side.
        if (!testing.tryLock()) throw ArtifactStorageTestRunningException()
        try {
            val connection = repository.connection(caller.orgId) ?: throw ArtifactStorageMissingException()
            val steps = Probe(connection, ArtifactKeys.orgRoot(connection.prefix, caller.orgId.toString())).run()
            val passed = steps.size == PROBE_STEPS && steps.all { it.ok }
            // The result belongs to the version that was tested; a save meanwhile makes it moot.
            if (!repository.recordTest(caller.orgId, connection.version, passed, clock.now())) {
                return StorageTestResult(false, steps + StorageTestStep("record", false, null, "storage_changed"))
            }
            return StorageTestResult(passed, steps)
        } finally {
            testing.unlock()
        }
    }

    override suspend fun saveSettings(caller: StorageCaller, settings: ArtifactSettings): ArtifactSettings {
        caller.requireAdministrator()
        StorageRules.validate(settings)
        if (settings.enabled) {
            val storage = repository.overview(caller.orgId).storage
                ?: throw ArtifactsInactiveException(InactiveReason.STORAGE_MISSING)
            if (storage.verifiedAt == null) throw ArtifactsInactiveException(InactiveReason.STORAGE_UNVERIFIED)
        }
        return repository.saveSettings(caller.orgId, settings, clock.now()) ?: throw ArtifactSettingsConflictException()
    }

    override suspend fun policy(caller: StorageCaller): ArtifactPolicy {
        val overview = repository.overview(caller.orgId)
        val inactive = overview.availability.reason
        val reason = when {
            inactive != null -> inactive.wire
            overview.settings.whoCanPublish == WhoCanPublish.ADMINS && !caller.administrator ->
                PublishRefusal.FORBIDDEN.wire
            !caller.mayWrite -> PublishRefusal.CREDENTIAL_SCOPE.wire
            else -> null
        }
        return ArtifactPolicy(reason)
    }

    private fun StorageCaller.requireAdministrator() {
        if (!administrator || !person) throw ArtifactAdministratorRequiredException()
    }

    /** The probe sequence of one test, every step bounded by what remains of [deadline]. */
    private inner class Probe(private val connection: ArtifactStorageConnection, root: String) {
        private val key = root + ".ostrom-probe-" + probeId()
        private val body = "Ostrom artifact storage probe $key".encodeToByteArray()
        private val steps = mutableListOf<StorageTestStep>()
        private val endsAt = clock.now() + deadline

        suspend fun run(): List<StorageTestStep> {
            val written = step("put") {
                objects.put(connection, key, body, "text/plain")
                null
            }
            if (written) {
                step("get") {
                    val read = objects.get(connection, key) ?: return@step "missing"
                    if (digest(read.bytes) == digest(body)) null else "digest_mismatch"
                }
                step("list") {
                    val listed = objects.list(connection, key, maxKeys = LISTED).keys.any { it.key == key }
                    if (listed) null else "not_listed"
                }
                step("delete") {
                    when {
                        objects.delete(connection, listOf(key)).isNotEmpty() -> "not_deleted"
                        objects.head(connection, key) != null -> "still_present"
                        else -> null
                    }
                }
            } else if (steps.single().status == null) {
                // A write that timed out or lost its answer may still have landed: remove it, quietly and briefly.
                cleanUp()
            }
            return steps
        }

        /** Runs one step; its failure is its code, never a message. */
        private suspend fun step(name: String, block: suspend () -> String?): Boolean {
            val done = try {
                val failure = withTimeout((endsAt - clock.now()).coerceAtLeast(MIN_STEP)) { block() }
                StorageTestStep(name, failure == null, null, failure)
            } catch (_: TimeoutCancellationException) {
                StorageTestStep(name, false, null, "timeout")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: ObjectStoreException) {
                StorageTestStep(name, false, refused.status, refused.code)
            }
            steps += done
            return done.ok
        }

        private suspend fun cleanUp() {
            try {
                withTimeout(CLEANUP) { objects.delete(connection, listOf(key)) }
            } catch (_: TimeoutCancellationException) {
                // Best effort: the probe is a few bytes under the organization's folder.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ObjectStoreException) {
                // Best effort, as above.
            }
        }
    }
}

private val TEST_DEADLINE: Duration = 80.seconds
private val CLEANUP: Duration = 10.seconds
private val MIN_STEP: Duration = 1.seconds
private const val PROBE_STEPS = 4
private const val LISTED = 10
