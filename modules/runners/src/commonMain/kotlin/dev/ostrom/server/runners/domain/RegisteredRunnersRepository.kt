// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The runners organizations registered. */
interface RegisteredRunnersRepository {
    /**
     * Stores a new runner, unless its organization already has one of that name or [max] of them. The count and the
     * write are serialized per organization, so two concurrent registrations never pass the cap together.
     *
     * @param runner The runner.
     * @param max The most runners an organization may have.
     *
     * @return What was done.
     */
    suspend fun register(runner: RegisteredRunner, max: Int): RunnerRegistrationOutcome

    /**
     * Lists an organization's runners by name.
     *
     * @param orgId The organization.
     * @param limit The most to return.
     *
     * @return The runners.
     */
    suspend fun list(orgId: Uuid, limit: Int): List<RegisteredRunner>

    /**
     * Removes a runner of an organization; its key no longer exists with it.
     *
     * @param orgId The organization.
     * @param id The runner.
     *
     * @return True when it existed in that organization.
     */
    suspend fun delete(orgId: Uuid, id: Uuid): Boolean

    /**
     * The organization and key hash of a runner, read across organizations (the heartbeat names only the runner).
     *
     * @param id The runner.
     *
     * @return Them, or null when no runner has that id.
     */
    suspend fun keyOf(id: Uuid): RegisteredRunnerKey?

    /**
     * Notes a runner's sign of life, if it still has that key; an older sign never replaces a newer one.
     *
     * @param orgId The organization.
     * @param id The runner.
     * @param keyHash The hash of the key it presented.
     * @param at When.
     *
     * @return False when that organization no longer has this runner with this key (removed meanwhile).
     */
    suspend fun touch(orgId: Uuid, id: Uuid, keyHash: String, at: Instant): Boolean
}
