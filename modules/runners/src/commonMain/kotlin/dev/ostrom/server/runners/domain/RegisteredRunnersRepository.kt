// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.domain

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
}
