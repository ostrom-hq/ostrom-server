// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.domain

import run.ostrom.server.artifacts.domain.storage.ArtifactContents
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A signed address of a version's pages on the content origin.
 *
 * @property artifactId The artifact.
 * @property version The version.
 * @property contentUrl The entry page's address.
 * @property expiresAt When the address stops working.
 */
data class ArtifactTicket(
    val artifactId: Uuid,
    val version: Int,
    val contentUrl: String,
    val expiresAt: Instant,
)

/**
 * Serves artifact pages on the isolated content origin: by a sealed ticket for members (minted through the API for a
 * person, with their access checked then and again at most every 30 seconds), or by the public link of a `link`
 * artifact.
 */
interface ViewArtifactUseCase {
    /**
     * Mints a ticket for a version the caller reads.
     *
     * @param version The version; the latest when null.
     *
     * @return The ticket.
     *
     * @throws ArtifactPersonRequiredException When no person asks (a token a program can present).
     * @throws ArtifactTicketsRateLimitedException Past [ViewArtifactUseCaseImpl.TICKETS_PER_MINUTE] for the credential.
     * @throws ArtifactNotFoundException When the artifact or the version is unknown or unreadable.
     * @throws ArtifactPagesUnavailableException When no content origin is configured.
     */
    suspend fun ticket(caller: ArtifactCaller, id: Uuid, version: Int?): ArtifactTicket

    /**
     * A file under a ticket.
     *
     * @return The file, or null when the ticket is forged, expired or for another artifact, its reader no longer reads
     *   the artifact (signed out, left the organization, lost access), or the file is unknown.
     */
    suspend fun byTicket(id: Uuid, ticket: String, path: String): ServedFile?

    /**
     * A file of the latest version under a public link.
     *
     * @return The file, or null when the link is unknown or ended, or the file is unknown.
     */
    suspend fun byLink(slug: String, path: String): ServedFile?

    /**
     * The public address of a `link` artifact.
     *
     * @return The address, or null when the artifact has no link or no content origin is configured.
     */
    fun publicUrl(artifact: Artifact): String?
}

/**
 * [ViewArtifactUseCase], as TeamCode's `ArtifactViewing`: a ticket is sealed ([ViewTicketSeal]) and names the member
 * and the credential that asked; it lasts 8 hours, rounded down to 10 minutes so reloading reuses the same address
 * (and the browser's cache). Each replica checks a ticket again at most every [recheck] (10,000 tickets cached): the
 * credential still live, the member still in the organization, the artifact still readable by them and the version
 * still there (the file read finds none otherwise); so signing out, leaving or losing access stops its pages within
 * that time, and a deleted artifact at once. At most [TICKETS_PER_MINUTE] tickets a minute per credential on each
 * replica.
 */
@Suppress("LongParameterList") // The ports a page needs, and two bounds the tests shorten.
internal class ViewArtifactUseCaseImpl(
    private val repository: ArtifactsRepository,
    private val contents: ArtifactContents,
    private val seal: ViewTicketSeal,
    private val viewers: ViewerChecks,
    private val locations: ArtifactLocations,
    private val clock: Clock,
    private val recheck: Duration = 30.seconds,
    private val maxCached: Int = MAX_CACHED,
) : ViewArtifactUseCase {
    private val rules = ArtifactAccessRules(repository)
    private val lock = Mutex()
    private val windows = LinkedHashMap<String, Window>()
    private val grants = LinkedHashMap<String, Instant>()

    override suspend fun ticket(caller: ArtifactCaller, id: Uuid, version: Int?): ArtifactTicket {
        val credential = caller.credentialId
        val subject = caller.subjectId
        if (!caller.person || credential == null || subject == null) {
            throw ArtifactPersonRequiredException(PAGES_FOR_PEOPLE)
        }
        contents.requireActive(caller.orgId)
        val origin = locations.contentOrigin ?: throw ArtifactPagesUnavailableException()
        admit(credential)
        val artifact = rules.readable(caller, id)
        val number = version ?: artifact.latestVersion
        val found = repository.version(artifact.orgId, id, number) ?: throw ArtifactNotFoundException()
        val expiry = (clock.now() + LIFETIME).epochSeconds.let { it - it % ROUNDING.inWholeSeconds }
        val sealed = seal.seal(ViewTicket(id, number, caller.memberId, subject, credential, expiry))
        return ArtifactTicket(id, number, "$origin/a/$id/$sealed/${found.entry}", Instant.fromEpochSeconds(expiry))
    }

    override suspend fun byTicket(id: Uuid, ticket: String, path: String): ServedFile? {
        val opened = seal.open(id, ticket)?.takeIf { it.expiresAt > clock.now().epochSeconds } ?: return null
        val artifact = repository.getById(id)?.takeIf { contents.active(it.orgId) && granted(ticket, it, opened) }
        return artifact?.let { serveFile(repository, contents, it, opened.version, path) }
    }

    override suspend fun byLink(slug: String, path: String): ServedFile? {
        val artifact = repository.getByLinkSlug(slug)
            ?.takeIf { it.access == ArtifactAccess.LINK && contents.active(it.orgId) } ?: return null
        return serveFile(repository, contents, artifact, artifact.latestVersion, path)
    }

    override fun publicUrl(artifact: Artifact): String? {
        val origin = locations.contentOrigin ?: return null
        return artifact.linkSlug?.let { "$origin/l/$it/" }
    }

    /** Refuses a credential past its tickets of the minute (a fixed window), naming when to retry. */
    private suspend fun admit(credential: String) {
        val now = clock.now()
        val retryAfter = lock.withLock {
            val window = windows[credential]?.takeIf { now - it.start < MINUTE } ?: Window(now).also {
                windows.remove(credential)
                windows[credential] = it
                if (windows.size > maxCached) windows.remove(windows.keys.first())
            }
            if (window.count < TICKETS_PER_MINUTE) {
                window.count++
                null
            } else {
                (window.start + MINUTE - now).inWholeSeconds + 1
            }
        }
        if (retryAfter != null) throw ArtifactTicketsRateLimitedException(retryAfter)
    }

    /** Whether the ticket's reader still reads the artifact, asked again at most every [recheck]. */
    private suspend fun granted(value: String, artifact: Artifact, ticket: ViewTicket): Boolean {
        val now = clock.now()
        val checkedAt = lock.withLock { grants[value] }
        if (checkedAt != null && now - checkedAt < recheck) return true
        val reader = ArtifactCaller(artifact.orgId, ticket.memberId, person = true, credentialId = ticket.credentialId)
        val still = rules.reads(reader, artifact) && viewers.stillSignedIn(artifact.orgId, ticket)
        lock.withLock {
            grants.remove(value)
            if (still) grants[value] = now
            if (grants.size > maxCached) grants.remove(grants.keys.first())
        }
        return still
    }

    private class Window(val start: Instant) {
        var count = 0
    }

    /** The lifetimes and bounds. */
    companion object {
        /** A member's ticket: a working day at most, and never beyond the credential that asked. */
        val LIFETIME: Duration = 8.hours

        /** Expiries are rounded down to this step, so reloading gives the same addresses for a while. */
        val ROUNDING: Duration = 10.minutes

        /** Tickets a credential may ask for per minute: opening, switching versions and renewing stay far below. */
        const val TICKETS_PER_MINUTE = 60

        private val MINUTE: Duration = 1.minutes
        private const val MAX_CACHED = 10_000
        private const val PAGES_FOR_PEOPLE =
            "Only a person opens an artifact's pages, in the console or the app: a token cannot."
    }
}
