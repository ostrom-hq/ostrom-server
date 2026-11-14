// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * What a Kubernetes session runs on, copied from its organization's cloud profile when it is created
 * (`runners.cloud.profiles.v1`, TeamCode's `profileId`, `profileVersion`, `clusterId`, `namespace`, `imageReference`
 * and `workspace`): a later version of the profile never changes a session that exists. A session of the former
 * variables, back-filled once onto the imported cluster, has no [origin].
 *
 * @property origin The profile and the version it was copied from, or null for a session of the former variables.
 * @property clusterId The cluster of the instance the session's objects live on.
 * @property namespace The namespace they live in.
 * @property image The image of the session's containers: the profile's, or the override asked at creation.
 * @property imagePullSecrets Secrets of [namespace] the image is pulled with.
 * @property resources The agent's container resources; null for a session of the former variables, which keeps the
 *   former ones.
 * @property workspaceGibibytes The workspace claim's size.
 * @property storageClass The claim's storage class (the profile's, else the cluster's default); null for the cluster's.
 */
@Serializable
data class SessionProfile(
    val origin: ProfileOrigin? = null,
    val clusterId: String,
    val namespace: String,
    val image: String,
    val imagePullSecrets: List<String> = emptyList(),
    val resources: SessionResources? = null,
    val workspaceGibibytes: Int,
    val storageClass: String? = null,
)

/**
 * The profile a session was copied from (TeamCode's `profile {id, version}`).
 *
 * @property id The profile.
 * @property version The version copied.
 */
@Serializable
data class ProfileOrigin(val id: String, val version: Int)

/**
 * The agent's container resources, requests equal to limits (TeamCode's).
 *
 * @property cpuMillis CPU, in thousandths of a core.
 * @property memoryMebibytes Memory, in MiB.
 */
@Serializable
data class SessionResources(val cpuMillis: Int, val memoryMebibytes: Int)

/**
 * A profile of the organization at its current version, as the session rules read it.
 *
 * @property id The profile.
 * @property version Its current version.
 * @property assignedMemberIds The members allowed to use it; owners and admins always may.
 * @property clusterId Its cluster.
 * @property namespace Its namespace.
 * @property image Its image.
 * @property allowedImageOverrides The images a member may ask for instead (`*` matches any run of characters).
 * @property imagePullSecrets Its pull secrets.
 * @property resources Its resources.
 * @property workspaceGibibytes Its workspace size.
 * @property storageClass Its storage class, or null for the cluster's default.
 */
data class ProfileOption(
    val id: Uuid,
    val version: Int,
    val assignedMemberIds: Set<String>,
    val clusterId: String,
    val namespace: String,
    val image: String,
    val allowedImageOverrides: List<String>,
    val imagePullSecrets: List<String>,
    val resources: SessionResources,
    val workspaceGibibytes: Int,
    val storageClass: String?,
)

/**
 * A cluster a profile names, as reachable now.
 *
 * @property allowedNamespaces The namespaces it allows.
 * @property defaultStorageClass Its default storage class, or null.
 */
data class ProfileCluster(val allowedNamespaces: List<String>, val defaultStorageClass: String?)

/** The organizations' cloud profiles and the instance's clusters, as the session rules read them. */
interface CloudProfileCatalog {
    /**
     * The organization's profiles.
     *
     * @param orgId The organization.
     *
     * @return They, at their current version.
     */
    suspend fun profiles(orgId: Uuid): List<ProfileOption>

    /**
     * One profile of the organization.
     *
     * @param orgId The organization.
     * @param id The profile.
     *
     * @return It, or null when it is none of the organization's.
     */
    suspend fun profile(orgId: Uuid, id: Uuid): ProfileOption?

    /**
     * A cluster, when the server can reach it.
     *
     * @param id The cluster.
     *
     * @return It, or null when it is gone or its credentials must be entered again.
     */
    suspend fun cluster(id: String): ProfileCluster?
}

/** Which profile a new session runs on, and whether its cluster still takes it at each start. */
interface SessionProfiles {
    /**
     * The snapshot a new session keeps.
     *
     * @param caller The member, who becomes the owner.
     * @param profileId The profile asked for, or null for the one the owner may use.
     * @param image The image asked for instead of the profile's, or null.
     *
     * @return The snapshot; null where sessions run on no profile (Docker, registered runners).
     *
     * @throws SessionProfileNotFoundException When the organization has no such profile.
     * @throws ProfileUnassignedException When the owner may not use it.
     * @throws ProfileRequiredException When none was asked and the owner may use none or several.
     * @throws ImageNotAllowedException When the image is neither the profile's nor an allowed override.
     * @throws ClusterUnavailableException When the profile's cluster is gone or its credentials must be entered again.
     * @throws NamespaceNotAllowedException When the cluster no longer allows the profile's namespace.
     */
    suspend fun choose(caller: Caller, profileId: Uuid?, image: String?): SessionProfile?

    /**
     * Why a start of the session cannot go on: its cluster is gone, or no longer allows its namespace.
     *
     * @param session The session about to start.
     *
     * @return The failure, or null.
     */
    suspend fun startFailure(session: CloudSession): SessionFailure?

    /**
     * The profile of a session created before sessions had one (the former `OSTROM_RUNNERS_KUBERNETES_*` variables),
     * given to it at its next step whatever happened at the upgrade: its objects live on the cluster those variables
     * named, imported into the console then if it is not yet.
     *
     * @return It; null where sessions run on no profile, or when that cluster cannot be imported.
     */
    suspend fun legacy(): SessionProfile?
}

/** Sessions on Docker or on registered runners: no profile, nothing checked. */
object NoSessionProfiles : SessionProfiles {
    override suspend fun choose(caller: Caller, profileId: Uuid?, image: String?): SessionProfile? = null

    override suspend fun startFailure(session: CloudSession): SessionFailure? = null

    override suspend fun legacy(): SessionProfile? = null
}

/** The profile of the sessions of the former variables, on the cluster they named ([SessionProfiles.legacy]). */
fun interface LegacySessionProfile {
    /**
     * The profile.
     *
     * @return It, or null when the former variables' cluster cannot be imported.
     */
    suspend fun profile(): SessionProfile?
}

/**
 * [SessionProfiles] of Kubernetes sessions, TeamCode's create and start rules (CloudSessionService.create,
 * ReconcileCloudSessionsUseCase.start).
 *
 * @param catalog The profiles and the clusters.
 * @param delivery The URL a session's runner calls back, checked at each start.
 * @param legacyProfile The profile of the sessions of the former variables.
 */
class CatalogSessionProfiles(
    private val catalog: CloudProfileCatalog,
    private val delivery: RunnerDeliverySource,
    private val legacyProfile: LegacySessionProfile = LegacySessionProfile { null },
) : SessionProfiles {
    override suspend fun choose(caller: Caller, profileId: Uuid?, image: String?): SessionProfile {
        val profile = if (profileId == null) {
            catalog.profiles(caller.orgId).filter { usableBy(caller, it) }.singleOrNull()
                ?: throw ProfileRequiredException()
        } else {
            // An id is random and listed only to whom may use it: saying it exists but is not assigned leaks nothing.
            val found = catalog.profile(caller.orgId, profileId) ?: throw SessionProfileNotFoundException()
            if (!usableBy(caller, found)) throw ProfileUnassignedException()
            found
        }
        // A blank image is none asked, as a blank model or prompt is.
        val requested = image?.takeIf { it.isNotBlank() }?.trim()
        if (requested != null) {
            ensureValid(IMAGE.matches(requested)) { "The image is not an image reference." }
            val allowed = requested == profile.image ||
                profile.allowedImageOverrides.any { matchesImagePattern(it, requested) }
            if (!allowed) throw ImageNotAllowedException()
        }
        val chosen = requested ?: profile.image
        val cluster = catalog.cluster(profile.clusterId) ?: throw ClusterUnavailableException()
        if (profile.namespace !in cluster.allowedNamespaces) throw NamespaceNotAllowedException()
        return SessionProfile(
            origin = ProfileOrigin(profile.id.toString(), profile.version),
            clusterId = profile.clusterId,
            namespace = profile.namespace,
            image = chosen,
            imagePullSecrets = profile.imagePullSecrets,
            resources = profile.resources,
            workspaceGibibytes = profile.workspaceGibibytes,
            storageClass = profile.storageClass ?: cluster.defaultStorageClass,
        )
    }

    @Suppress("ReturnCount") // No profile, then no cluster, then its namespace, then no URL to call back.
    override suspend fun startFailure(session: CloudSession): SessionFailure? {
        // A Kubernetes session always has one (created with it, or given the former variables'); never act without.
        val profile = session.profile ?: return CLUSTER_UNAVAILABLE
        val cluster = catalog.cluster(profile.clusterId) ?: return CLUSTER_UNAVAILABLE
        // A cluster narrowed after the session was created: never create objects outside its allowed namespaces.
        if (profile.namespace !in cluster.allowedNamespaces) return NAMESPACE_NOT_ALLOWED
        // A runner without the server's URL could never report: no pod is started for nothing.
        return SERVER_URL_MISSING.takeIf { delivery.current().serverUrl.isBlank() }
    }

    override suspend fun legacy(): SessionProfile? = legacyProfile.profile()

    private fun usableBy(caller: Caller, profile: ProfileOption): Boolean =
        caller.admin || caller.memberId in profile.assignedMemberIds

    /** Constants and readers of the enclosing type. */
    companion object {
        private val IMAGE = Regex("[A-Za-z0-9][A-Za-z0-9._/:@+-]{0,511}")

        /**
         * Whether an image matches an override: `*` matches any run of characters, everything else is literal.
         *
         * @param pattern The override.
         * @param image The image asked for.
         *
         * @return Whether it matches.
         */
        fun matchesImagePattern(pattern: String, image: String): Boolean =
            Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) }).matches(image)
    }
}

/** The failure of a start whose runner would have no server URL to call back. */
private val SERVER_URL_MISSING = SessionFailure(
    "server_url_missing",
    "The server URL a session's runner calls back is not set: set the cloud runtime's server URL (Admin → Cloud), " +
        "or OSTROM_PUBLIC_URL.",
    retryable = true,
)

/** The failure of a start whose cluster is gone or must be entered again. */
private val CLUSTER_UNAVAILABLE = SessionFailure(
    "cluster_unavailable",
    "The session's cluster is no longer configured, or its credentials must be entered again.",
    retryable = true,
)

/** The failure of a start whose namespace the cluster no longer allows. */
private val NAMESPACE_NOT_ALLOWED = SessionFailure(
    "namespace_not_allowed",
    "The session's namespace is no longer allowed on its cluster.",
    retryable = true,
)
