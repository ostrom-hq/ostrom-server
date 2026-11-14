// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.hooks.domain

import run.ostrom.server.skills.domain.ArtifactFile
import run.ostrom.server.skills.domain.MemberDecision
import run.ostrom.server.skills.domain.ValidatedArtifact
import kotlin.uuid.Uuid

/**
 * The hook catalog's storage. Every block runs in one transaction confined to its organization (row-level security);
 * [write] also serializes the organization's hook writes, so two replicas can never both pass a rule that depends on
 * the current state (a limit, a pin, the audience a key may set).
 */
interface HooksRepository {
    /**
     * Reads in one transaction.
     *
     * @param orgId The organization.
     * @param block The reads.
     *
     * @return What [block] returns.
     */
    suspend fun <T> read(orgId: Uuid, block: suspend HooksStore.() -> T): T

    /**
     * Writes in one transaction, after every other hook write of the organization has committed. A failure inside
     * rolls everything back, a hook created by a refused first publication included.
     *
     * @param orgId The organization.
     * @param block The reads and writes.
     *
     * @return What [block] returns.
     */
    suspend fun <T> write(orgId: Uuid, block: suspend HooksStore.() -> T): T
}

/** The statements of one transaction of [HooksRepository], all in its organization. */
// One store per aggregate: splitting it would only spread the same tables over several classes.
@Suppress("TooManyFunctions")
interface HooksStore {
    /** The hook named [name], or null. */
    suspend fun hook(name: String): Hook?

    /** The hooks, newest change first, at most [limit]. */
    suspend fun catalog(limit: Int): List<Hook>

    /** How many hooks there are. */
    suspend fun countHooks(): Long

    /** Stores a new hook. */
    suspend fun insertHook(hook: Hook)

    /** Writes a hook's pin, audience and update time. */
    suspend fun updateHook(hook: Hook)

    /** A version of [hookId] by its label, or null. */
    suspend fun version(hookId: Uuid, label: String): HookVersion?

    /** [hookId]'s versions, newest first, at most [limit]. */
    suspend fun versions(hookId: Uuid, limit: Int): List<HookVersion>

    /** How many versions [hookId] has. */
    suspend fun countVersions(hookId: Uuid): Long

    /** Stores a version, its definition and its files. */
    suspend fun insertVersion(version: HookVersion, artifact: ValidatedArtifact)

    /** [memberId]'s decision row on [hookId], cleared or not, or null. */
    suspend fun assignment(hookId: Uuid, memberId: String): HookAssignment?

    /** Inserts or updates a decision row, by its id. */
    suspend fun saveAssignment(assignment: HookAssignment, updatedBy: String)

    /** [hookId]'s explicit decisions, by member id, at most [limit]. */
    suspend fun assignments(hookId: Uuid, limit: Int): List<MemberDecision>

    /** Every hook, by name, with its pinned version and [memberId]'s decision, at most [limit]. */
    suspend fun candidates(memberId: String, limit: Int): List<HookCandidate>

    /** A version's files, sorted by path. */
    suspend fun files(versionId: Uuid): List<ArtifactFile>
}
