// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts.domain

import kotlin.uuid.Uuid

/**
 * What a reading ticket grants: one version of one artifact, to one member through one credential, until [expiresAt].
 *
 * @property artifactId The artifact.
 * @property version The version.
 * @property memberId The member who asked ([ArtifactCaller.memberId]).
 * @property subjectId The person behind the credential ([ArtifactCaller.subjectId]).
 * @property credentialId The browser session or app token that asked ([ArtifactCaller.credentialId]).
 * @property expiresAt When it stops, in epoch seconds.
 */
data class ViewTicket(
    val artifactId: Uuid,
    val version: Int,
    val memberId: String,
    val subjectId: String,
    val credentialId: String,
    val expiresAt: Long,
)

/**
 * Seals reading tickets. A ticket is carried in the content path, where the artifact's own page can read it: it must
 * tell nothing of its reader, and nobody may forge or alter one.
 */
interface ViewTicketSeal {
    /**
     * Seals a ticket; the same ticket always gives the same string, so reloading a page reuses its address.
     *
     * @return The sealed ticket, URL-safe.
     */
    suspend fun seal(ticket: ViewTicket): String

    /**
     * Opens a sealed ticket for [artifactId].
     *
     * @return The ticket, or null when it is malformed, forged, altered or sealed for another artifact (expiry is the
     *   caller's to check).
     */
    suspend fun open(artifactId: Uuid, sealed: String): ViewTicket?
}

/** Whether the member and the credential a ticket names still stand. */
interface ViewerChecks {
    /**
     * @param orgId The artifact's organization.
     * @param ticket The ticket.
     *
     * @return True while the credential is live (not signed out, revoked or expired) and the member still belongs to
     *   the organization.
     */
    suspend fun stillSignedIn(orgId: Uuid, ticket: ViewTicket): Boolean
}
