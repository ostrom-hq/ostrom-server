// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts.domain

import run.ostrom.server.kernel.signing.InstanceSigner
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
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
 * Serves artifact pages on the isolated content origin: by a signed ticket for members (minted through the API,
 * with their access checked then), or by the public link of a `link` artifact.
 */
interface ViewArtifactUseCase {
    /**
     * Mints a ticket for a version the caller reads.
     *
     * @param version The version; the latest when null.
     *
     * @return The ticket.
     *
     * @throws ArtifactNotFoundException When the artifact or the version is unknown or unreadable.
     * @throws ArtifactPagesUnavailableException When no content origin is configured.
     */
    suspend fun ticket(caller: ArtifactCaller, id: Uuid, version: Int?): ArtifactTicket

    /**
     * A file under a ticket.
     *
     * @return The file, or null when the ticket is forged, expired or for another artifact, or the file is unknown.
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
 * [ViewArtifactUseCase]. A ticket is `<version>-<expiry>-<signature>`: the instance's Ed25519 signature over the
 * artifact id, the version and the expiry, in hex. It names no member, since the page can read its own address;
 * its lifetime is short (1 hour, rounded down to 10 minutes so reloading reuses the browser's cache), and a deleted
 * artifact stops serving at once.
 */
internal class ViewArtifactUseCaseImpl(
    private val repository: ArtifactsRepository,
    private val signer: InstanceSigner,
    private val locations: ArtifactLocations,
    private val clock: Clock,
) : ViewArtifactUseCase {
    private val rules = ArtifactAccessRules(repository)

    override suspend fun ticket(caller: ArtifactCaller, id: Uuid, version: Int?): ArtifactTicket {
        val origin = locations.contentOrigin ?: throw ArtifactPagesUnavailableException()
        val artifact = rules.readable(caller, id)
        val number = version ?: artifact.latestVersion
        val found = repository.version(artifact.orgId, id, number) ?: throw ArtifactNotFoundException()
        val expiry = clock.now() + LIFETIME
        val rounded = Instant.fromEpochSeconds(expiry.epochSeconds - expiry.epochSeconds % ROUNDING.inWholeSeconds)
        val signature = signer.sign(signed(id, number, rounded.epochSeconds)).toHexString()
        val ticket = "$number-${rounded.epochSeconds}-$signature"
        return ArtifactTicket(id, number, "$origin/a/$id/$ticket/${found.entry}", rounded)
    }

    override suspend fun byTicket(id: Uuid, ticket: String, path: String): ServedFile? {
        val parts = ticket.split('-')
        val number = parts[0].toIntOrNull()
        val expiry = parts.getOrNull(1)?.toLongOrNull()
        val signature = parts.getOrNull(2)?.takeIf { parts.size == TICKET_PARTS && HEX.matches(it) }
        val valid = number != null && expiry != null && signature != null &&
            expiry > clock.now().epochSeconds &&
            signer.verify(signed(id, number, expiry), signature.hexToByteArray())
        val artifact = if (valid) repository.getById(id) else null
        if (!valid || artifact == null) return null
        return serveFile(repository, artifact, number, path)
    }

    override suspend fun byLink(slug: String, path: String): ServedFile? {
        val artifact = repository.getByLinkSlug(slug)?.takeIf { it.access == ArtifactAccess.LINK } ?: return null
        return serveFile(repository, artifact, artifact.latestVersion, path)
    }

    override fun publicUrl(artifact: Artifact): String? {
        val origin = locations.contentOrigin ?: return null
        return artifact.linkSlug?.let { "$origin/l/$it/" }
    }

    private fun signed(id: Uuid, number: Int, expiry: Long): ByteArray =
        "artifact:$id:$number:$expiry".encodeToByteArray()

    private companion object {
        private val LIFETIME = 1.hours
        private val ROUNDING = 10.minutes
        const val TICKET_PARTS = 3
        private val HEX = Regex("(?:[0-9a-f]{2})+")
    }
}
