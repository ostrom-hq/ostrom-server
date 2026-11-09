// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.secrets.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A secret row with its envelope; the envelope never leaves the domain.
 *
 * @property secret The metadata.
 * @property envelope The sealed value.
 */
data class StoredSecret(val secret: Secret, val envelope: String)

/** Storage of secrets; every method is scoped by the org. */
interface SecretsRepository {
    /**
     * Inserts a secret.
     *
     * @param stored The secret and its envelope.
     *
     * @return False when another secret of the org has the same name and owner.
     */
    suspend fun create(stored: StoredSecret): Boolean

    /**
     * Reads one secret.
     *
     * @param orgId The org.
     * @param id The secret.
     *
     * @return The secret, or null when the org has no such secret.
     */
    suspend fun get(orgId: Uuid, id: Uuid): StoredSecret?

    /**
     * Reads several secrets of one org in one query.
     *
     * @param orgId The org.
     * @param ids The secrets.
     *
     * @return The ones found, in any order.
     */
    suspend fun getMany(orgId: Uuid, ids: Collection<Uuid>): List<StoredSecret>

    /**
     * Lists the org's secrets visible to a member: org secrets, and the member's own.
     *
     * @param orgId The org.
     * @param memberId The member, or null for every secret (admins).
     * @param after Keyset cursor: secrets strictly after it, by name then id.
     * @param limit The page size.
     *
     * @return The secrets, by name then id.
     */
    suspend fun list(orgId: Uuid, memberId: Uuid?, after: SecretCursor?, limit: Int): List<Secret>

    /**
     * Replaces a value and bumps the version.
     *
     * @param orgId The org.
     * @param id The secret.
     * @param envelope The new envelope.
     * @param updatedAt The change time.
     *
     * @return The secret after the change, or null when it does not exist.
     */
    suspend fun rotate(orgId: Uuid, id: Uuid, envelope: String, updatedAt: Instant, updatedBy: Uuid): Secret?

    /**
     * Finds secrets by name, in one query.
     *
     * @param ownerMemberId The member for personal secrets, null for org secrets.
     */
    suspend fun findManyByName(orgId: Uuid, ownerMemberId: Uuid?, names: Collection<String>): List<Secret>

    /** Org secrets (not personal ones) by name, with their envelopes. */
    suspend fun getOrgSecretsByName(orgId: Uuid, names: Collection<String>): List<StoredSecret>

    /**
     * Deletes a secret.
     *
     * @param orgId The org.
     * @param id The secret.
     *
     * @return True when a row was deleted.
     */
    suspend fun delete(orgId: Uuid, id: Uuid): Boolean
}
