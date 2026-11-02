// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A machine an organization registered to run its cloud sessions and workflow steps (the contract's
 * `registerRunner`). It authenticates with its own key, shown once at registration; only the key's hash is kept.
 *
 * @property id The runner.
 * @property orgId Its organization; another organization never sees it.
 * @property name Its name, unique in the organization whatever its case.
 * @property labels What it offers (`linux`, `gpu`), in the order given, without duplicates.
 * @property version The version it declared, if any.
 * @property keyHash The SHA-256 of its key, in lower-case hex.
 * @property registeredBy The member who registered it.
 * @property createdAt When it was registered.
 * @property lastSeenAt Its last sign of life, null before the first one.
 */
data class RegisteredRunner(
    val id: Uuid,
    val orgId: Uuid,
    val name: String,
    val labels: List<String>,
    val version: String?,
    val keyHash: String,
    val registeredBy: String,
    val createdAt: Instant,
    val lastSeenAt: Instant?,
)

/** What storing a new runner did. */
enum class RunnerRegistrationOutcome {
    /** It is stored. */
    REGISTERED,

    /** The organization already has a runner of that name: nothing is stored. */
    NAME_TAKEN,

    /** The organization already has as many runners as allowed: nothing is stored. */
    LIMIT_REACHED,
}

/**
 * What authenticates a registered runner: its organization and the hash of its key.
 *
 * @property orgId The organization.
 * @property keyHash The SHA-256 hex digest of its key.
 */
data class RegisteredRunnerKey(val orgId: Uuid, val keyHash: String) {
    override fun toString(): String = "RegisteredRunnerKey(orgId=$orgId, keyHash=***)"
}
