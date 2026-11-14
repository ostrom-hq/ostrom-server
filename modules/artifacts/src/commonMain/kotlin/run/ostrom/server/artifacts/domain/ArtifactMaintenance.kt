// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.domain

import run.ostrom.server.artifacts.domain.storage.ArtifactContents
import run.ostrom.server.artifacts.domain.storage.UploadChunks
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The rows the maintenance of artifacts finds and ends, each organization in its own transactions. */
interface ArtifactMaintenanceRepository {
    /**
     * The organizations with work due, across organizations (a `SECURITY DEFINER` function of the router).
     *
     * @param now Uploads and artifacts that expired by then.
     * @param drafts Drafts created before then.
     * @param publications Publications made before then, which the hourly limit no longer counts.
     */
    suspend fun organizations(now: Instant, drafts: Instant, publications: Instant, limit: Int): List<Uuid>

    /** Forgets the organization's publications made before [before]. */
    suspend fun forgetPublications(orgId: Uuid, before: Instant)

    /** The organization's uploads expired by [now], the oldest first. */
    suspend fun expiredUploads(orgId: Uuid, now: Instant, limit: Int): List<Uuid>

    /**
     * Ends an expired upload: its row goes, and the contents it stored that no version uses and no live upload
     * announced are marked for collection.
     */
    suspend fun endUpload(orgId: Uuid, uploadId: Uuid, now: Instant)

    /** The organization's artifacts past their `expires_at` and drafts created before [drafts], with no live upload. */
    suspend fun expired(orgId: Uuid, now: Instant, drafts: Instant, limit: Int): List<Uuid>

    /**
     * Deletes the artifact if it is still expired (a publication since the read moved its expiry forward).
     *
     * @return Its latest version when it was deleted (0 for a draft), null otherwise.
     */
    suspend fun deleteExpired(orgId: Uuid, artifactId: Uuid, now: Instant, drafts: Instant): Int?

    /** The organization's contents marked for collection that no live upload announced, the oldest mark first. */
    suspend fun markedBlobs(orgId: Uuid, now: Instant, limit: Int): List<ArtifactBlob>

    /** Forgets contents deleted from the bucket, unless a publication used one again since (its mark cleared). */
    suspend fun forgetBlobs(orgId: Uuid, blobs: List<ArtifactBlob>)
}

/**
 * A content of an artifact's folder.
 *
 * @property artifactId The artifact.
 * @property sha256 Its digest, its key in the folder.
 */
data class ArtifactBlob(val artifactId: Uuid, val sha256: String)

/**
 * The scheduled work of artifacts, TeamCode's `ArtifactMaintenance`: the publications the hourly limit no longer
 * counts are forgotten; an expired upload ends and its folder of chunks is deleted from the bucket; a draft never
 * committed for 24 hours and an artifact past its retention are deleted with their folder, the latter audited as
 * `artifact.retention_expired` by the system; the contents marked for collection are deleted from the bucket, then
 * forgotten. Each organization on its own: a failure is tried again at the next pass.
 *
 * @param repository The rows.
 * @param contents The artifacts' folders.
 * @param chunks The uploads' folders.
 * @param events The audit log.
 * @param clock The time.
 */
class ArtifactMaintenance(
    private val repository: ArtifactMaintenanceRepository,
    private val contents: ArtifactContents,
    private val chunks: UploadChunks,
    private val events: ArtifactEvents,
    private val clock: Clock = Clock.System,
) {
    /**
     * One pass over the organizations with work due.
     *
     * @return How many organizations it went through.
     */
    suspend fun run(): Int {
        val now = clock.now()
        val due = repository.organizations(now, now - DRAFT_LIFETIME, now - PUBLICATION_WINDOW, ORGANIZATIONS)
        due.forEach { orgId ->
            try {
                maintain(orgId, now)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Tried again at the next pass, behind the organizations that succeed.
            }
        }
        return due.size
    }

    private suspend fun maintain(orgId: Uuid, now: Instant) {
        repository.forgetPublications(orgId, now - PUBLICATION_WINDOW)
        for (upload in repository.expiredUploads(orgId, now, BATCH)) {
            // The folder first: an upload whose chunks the bucket kept stays, and is ended at a later pass.
            if (chunks.discard(orgId, upload)) repository.endUpload(orgId, upload, now)
        }
        val drafts = now - DRAFT_LIFETIME
        for (artifactId in repository.expired(orgId, now, drafts, BATCH)) {
            val latest = repository.deleteExpired(orgId, artifactId, now, drafts) ?: continue
            contents.remove(orgId, artifactId)
            if (latest > 0) events.record(system(orgId), "retention_expired", artifactId, emptyMap())
        }
        // Deleted from the bucket, then forgotten: while one is marked its artifact takes no upload and no version.
        val marked = repository.markedBlobs(orgId, now, BATCH)
        if (marked.isNotEmpty()) repository.forgetBlobs(orgId, contents.collect(orgId, marked))
    }

    private fun system(orgId: Uuid) = ArtifactCaller(
        orgId, SYSTEM, person = false, principal = AuthenticatedPrincipal(SYSTEM, PrincipalKind.SERVICE, emptySet()),
    )
}

/** A draft never committed is deleted after it (TeamCode). */
internal val DRAFT_LIFETIME = 24.hours

/** The publications the hourly limit counts. */
internal val PUBLICATION_WINDOW = 1.hours

/** The actor of the maintenance's audit lines. */
private const val SYSTEM = "system"

/** The organizations of one pass, and the rows of each step in one organization. */
private const val ORGANIZATIONS = 100
private const val BATCH = 100
