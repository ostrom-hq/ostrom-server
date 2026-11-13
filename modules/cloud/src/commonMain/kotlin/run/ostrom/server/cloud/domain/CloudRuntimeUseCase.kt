// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.domain

import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.settings.InstanceAuditRecord
import run.ostrom.server.kernel.settings.InstanceAuditTrail
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The cloud runtime the runtime driver and the session routes read: every replica sees a save within 30 seconds. */
interface CloudRuntimeSource {
    /**
     * The runtime in force.
     *
     * @return It; the defaults (off) while nothing is stored.
     */
    suspend fun current(): CloudRuntime
}

/** The instance's cloud runtime, read and saved by an administrator, seeded once from the former variables. */
interface CloudRuntimeUseCase : CloudRuntimeSource {
    /**
     * The runtime.
     *
     * @param caller An administrator of the instance.
     *
     * @return It.
     */
    suspend fun get(caller: CloudAdminCaller): CloudRuntime

    /**
     * Replaces the runtime (TeamCode 3b716702: an optimistic `updatedAt`; off refuses only new work).
     *
     * @param caller An administrator of the instance.
     * @param runtime The new runtime.
     * @param expectedUpdatedAt The `updatedAt` of the runtime edited, null while nothing was stored.
     *
     * @return The runtime saved.
     *
     * @throws InvalidCloudRuntimeException When a value is refused.
     * @throws CloudRuntimeConflictException When another save came first.
     */
    suspend fun save(caller: CloudAdminCaller, runtime: CloudRuntime, expectedUpdatedAt: Instant?): CloudRuntime

    /**
     * Seeds the runtime once from the former `OSTROM_RUNNERS_*` variables; afterwards they are ignored with a warning.
     * An invalid seed is stored off, keeping the values valid on their own, so the server still starts.
     *
     * @param environment Reads a variable.
     */
    suspend fun seed(environment: (String) -> String?)
}

/**
 * [CloudRuntimeUseCase].
 *
 * @param repository The stored runtime.
 * @param gate Who may.
 * @param audit The instance's audit log.
 * @param logger Where the deprecation is said.
 * @param clock The clock.
 * @param ttl How long a replica keeps the runtime it read.
 */
class CloudRuntimeUseCaseImpl(
    private val repository: CloudRuntimeRepository,
    private val gate: CloudAdminGate,
    private val audit: InstanceAuditTrail,
    private val logger: Logger,
    private val clock: Clock,
    private val ttl: Duration = 30.seconds,
) : CloudRuntimeUseCase {
    @Volatile
    private var held: Pair<Instant, CloudRuntime>? = null

    // Counts this replica's saves: a read begun before one never replaces what it saved.
    @Volatile
    private var saves = 0L

    override suspend fun current(): CloudRuntime {
        val now = clock.now()
        held?.takeIf { now - it.first < ttl }?.let { return it.second }
        val before = saves
        val read = repository.get() ?: CloudRuntime()
        if (saves == before) held = now to read
        return read
    }

    override suspend fun get(caller: CloudAdminCaller): CloudRuntime {
        gate.require(caller)
        return repository.get() ?: CloudRuntime()
    }

    override suspend fun save(
        caller: CloudAdminCaller,
        runtime: CloudRuntime,
        expectedUpdatedAt: Instant?,
    ): CloudRuntime {
        val userId = gate.require(caller)
        val next = normalize(runtime).copy(updatedAt = clock.nowMillis())
        runtimeProblem(next)?.let { throw InvalidCloudRuntimeException(it) }
        if (!repository.save(next, expectedUpdatedAt, userId)) throw CloudRuntimeConflictException()
        remember(next)
        audit.record(
            InstanceAuditRecord(
                userId,
                "cloud.runtime.saved",
                "cloud_runtime",
                "instance",
                mapOf("enabled" to next.enabled.toString()),
            ),
        )
        return next
    }

    override suspend fun seed(environment: (String) -> String?) {
        val set = listOf(DRIVER, IMAGE, SERVER_URL).filter { !environment(it).isNullOrEmpty() }
        when {
            set.isEmpty() -> Unit
            repository.get() != null -> logger.warn(
                "Ignored deprecated variables: the cloud runtime is stored and changed in the console only.",
                mapOf("variables" to set.joinToString(",")),
            )
            else -> store(seeded(environment), set)
        }
    }

    private suspend fun store(candidate: CloudRuntime, variables: List<String>) {
        val runtime = candidate.takeIf { runtimeProblem(it) == null } ?: disabled(candidate)
        if (!repository.save(runtime, null, null)) return
        remember(runtime)
        audit.record(InstanceAuditRecord(null, "cloud.runtime.imported", "cloud_runtime", "instance"))
        logger.warn(
            "Imported deprecated variables into the cloud runtime, once: change it in the console (Admin → Cloud) " +
                "and remove the variables.",
            mapOf("variables" to variables.joinToString(","), "enabled" to runtime.enabled),
        )
    }

    private fun seeded(environment: (String) -> String?): CloudRuntime {
        val enabled = environment(DRIVER)?.trim()?.lowercase() == "kubernetes"
        // The image the runners module used when the variable was unset.
        return seededRuntime(enabled, environment(IMAGE) ?: DEFAULT_IMAGE.takeIf { enabled }, environment)
    }

    private fun seededRuntime(enabled: Boolean, image: String?, environment: (String) -> String?) = normalize(
        CloudRuntime(
            enabled = enabled,
            runnerImage = image,
            serverUrl = environment(SERVER_URL),
            updatedAt = clock.nowMillis(),
        ),
    )

    // Off, keeping only the values valid on their own, so an invalid variable never prevents the start.
    private fun disabled(candidate: CloudRuntime): CloudRuntime {
        val base = CloudRuntime(updatedAt = candidate.updatedAt)
        fun valid(runtime: CloudRuntime) = runtimeProblem(runtime) == null
        return base.copy(
            runnerImage = candidate.runnerImage.takeIf { valid(base.copy(runnerImage = it)) },
            serverUrl = candidate.serverUrl.takeIf { valid(base.copy(serverUrl = it)) },
        )
    }

    private fun remember(runtime: CloudRuntime) {
        saves++
        held = clock.now() to runtime
    }

    private companion object {
        const val DRIVER = "OSTROM_RUNNERS_DRIVER"
        const val IMAGE = "OSTROM_RUNNERS_IMAGE"
        const val SERVER_URL = "OSTROM_RUNNERS_SERVER_URL"
        const val DEFAULT_IMAGE = "ghcr.io/ostrom-hq/ostrom-runner:latest"

        fun normalize(runtime: CloudRuntime) = runtime.copy(
            runnerImage = runtime.runnerImage.trimmedOrNull(),
            runnerBinary = runtime.runnerBinary.trim().ifEmpty { CloudRuntime.DEFAULT_RUNNER_BINARY },
            serverUrl = runtime.serverUrl.trimmedOrNull()?.trimEnd('/'),
            workspaceAccessMode = runtime.workspaceAccessMode.trim(),
            memberTransfers = runtime.memberTransfers.trim().lowercase(),
        )
    }
}
