// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.cloud.domain.CloudClustersUseCase
import run.ostrom.server.cloud.domain.CloudRuntimeSource
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.runners.domain.LegacySessionProfile
import run.ostrom.server.runners.domain.SessionProfile

/**
 * The Kubernetes sessions of the former `OSTROM_RUNNERS_*` variables: their cluster is imported into the console
 * ([CloudClustersUseCase.importLegacy]) at the first start that finds no cluster, so an upgrade needs no administrator,
 * and a session created before cloud profiles is given one on it at its next step ([profile]), with the former
 * workspace size and pull secret, so a paused session resumes on its claim. Asked at each such step, the import cannot
 * be skipped by a crash or by a cluster an administrator made before the upgrade. The variables a profile now holds
 * are said to be ignored at each start.
 *
 * @param clusters The console's clusters.
 * @param runtime The cloud runtime, for the image the former sessions ran.
 * @param fallbackImage The image they ran while the runtime names none (`OSTROM_RUNNERS_IMAGE`).
 * @param logger Where the ignored variables are said.
 * @param environment Reads a variable.
 */
class LegacyKubernetesImport(
    private val clusters: CloudClustersUseCase,
    private val runtime: CloudRuntimeSource,
    private val fallbackImage: String,
    private val logger: Logger,
    private val environment: (String) -> String?,
) : LegacySessionProfile {
    /** At each start of a server whose sessions run on Kubernetes: imports the cluster when none exists. */
    suspend fun start() {
        warnIgnored()
        clusters.importLegacy(environment)
    }

    override suspend fun profile(): SessionProfile? {
        val cluster = clusters.legacyCluster(environment) ?: return null
        return SessionProfile(
            clusterId = cluster.id.toString(),
            // Where the former variables put their objects, whatever the cluster allows now (checked at each start).
            namespace = environment(NAMESPACE).trimmedOrNull() ?: DEFAULT_NAMESPACE,
            image = runtime.current().runnerImage.trimmedOrNull() ?: fallbackImage,
            imagePullSecrets = listOfNotNull(environment(PULL_SECRET).trimmedOrNull()),
            workspaceGibibytes = gibibytes(environment(WORKSPACE_SIZE)),
            storageClass = cluster.defaultStorageClass,
        )
    }

    private fun warnIgnored() {
        val profileFields = listOf(WORKSPACE_SIZE, PULL_SECRET).filter { environment(it).trimmedOrNull() != null }
        if (profileFields.isNotEmpty()) {
            logger.warn(
                "Ignored deprecated variables: a session's workspace size and image pull secrets are its cloud " +
                    "profile's (Admin → Cloud).",
                mapOf("variables" to profileFields.joinToString(",")),
            )
        }
    }

    private companion object {
        const val WORKSPACE_SIZE = "OSTROM_RUNNERS_WORKSPACE_SIZE"
        const val NAMESPACE = "OSTROM_RUNNERS_KUBERNETES_NAMESPACE"
        const val DEFAULT_NAMESPACE = "ostrom"
        const val PULL_SECRET = "OSTROM_RUNNERS_KUBERNETES_IMAGE_PULL_SECRET"
        const val DEFAULT_GIBIBYTES = 10
        const val GIBIBYTES_PER_TEBIBYTE = 1024
        val QUANTITY = Regex("([0-9]+)(Gi|Ti)")

        fun String?.trimmedOrNull(): String? = if (this == null) null else trim().ifEmpty { null }

        /** The former size (`10Gi`, `1Ti`) in GiB; the former default when it is unset or in another unit. */
        @Suppress("ReturnCount") // Unset or unreadable, then too large.
        fun gibibytes(raw: String?): Int {
            val match = raw?.let { QUANTITY.matchEntire(it.trim()) } ?: return DEFAULT_GIBIBYTES
            val value = match.groupValues[1].toIntOrNull() ?: return DEFAULT_GIBIBYTES
            return if (match.groupValues[2] == "Ti") value * GIBIBYTES_PER_TEBIBYTE else value
        }
    }
}
