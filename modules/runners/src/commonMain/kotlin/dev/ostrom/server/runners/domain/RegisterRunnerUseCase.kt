// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.domain

import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * What registering a runner asks for.
 *
 * @property name Its name.
 * @property labels What it offers.
 * @property version The version it declares, if any.
 */
data class RunnerRegistration(val name: String, val labels: List<String>, val version: String?)

/**
 * A runner just registered, with its key.
 *
 * @property runner The runner.
 * @property key Its key: returned this once, never stored, never logged.
 */
data class NewRunner(val runner: RegisteredRunner, val key: String) {
    override fun toString(): String = "NewRunner(runner=$runner, key=***)"
}

/** Registers a runner for the caller's organization (owners and admins). */
interface RegisterRunnerUseCase {
    /**
     * Registers it.
     *
     * @param caller The member.
     * @param registration The runner.
     *
     * @return The runner and its key.
     *
     * @throws RunnerAdminRequiredException When the caller is neither an owner nor an admin.
     * @throws InvalidCloudSessionRequestException When the name, a label or the version is malformed.
     * @throws RunnerNameTakenException When the organization already has a runner of that name.
     * @throws RunnerLimitException When the organization already has [MAX_RUNNERS] runners.
     */
    suspend operator fun invoke(caller: Caller, registration: RunnerRegistration): NewRunner
}

/**
 * [RegisterRunnerUseCase] on the repository.
 *
 * @param repository The runners.
 * @param tokens Mints the key and hashes it.
 * @param clock The time.
 */
class RegisterRunnerUseCaseImpl(
    private val repository: RegisteredRunnersRepository,
    private val tokens: RunnerTokens,
    private val clock: Clock,
) : RegisterRunnerUseCase {
    override suspend fun invoke(caller: Caller, registration: RunnerRegistration): NewRunner {
        if (!caller.admin) throw RunnerAdminRequiredException()
        val name = registration.name.trim()
        ensureValid(name.length in 1..MAX_NAME && name.none { it.isISOControl() }) {
            "name is 1 to $MAX_NAME characters, without control characters."
        }
        val labels = registration.labels.distinct()
        ensureValid(labels.size <= MAX_LABELS) { "A runner has at most $MAX_LABELS labels." }
        ensureValid(labels.all { LABEL.matches(it) }) {
            "A label is 1 to 64 letters, digits, '.', '_', ':' or '-', and starts with a letter or a digit."
        }
        val version = registration.version
        ensureValid(version == null || VERSION.matches(version)) {
            "version is 1 to 64 letters, digits, '.', '+', '_' or '-', and starts with a letter or a digit."
        }
        val key = tokens.mint()
        val runner = RegisteredRunner(
            id = Uuid.random(),
            orgId = caller.orgId,
            name = name,
            labels = labels,
            version = version,
            keyHash = tokens.hash(key),
            registeredBy = caller.memberId,
            createdAt = clock.now(),
            lastSeenAt = null,
        )
        return repository.register(runner, MAX_RUNNERS).answer(NewRunner(runner, key))
    }

    /**
     * What the registration answers once stored.
     *
     * @param created The runner and its key.
     *
     * @return [created] when it was stored.
     *
     * @throws RunnerNameTakenException When the name was taken.
     * @throws RunnerLimitException When the organization was full.
     */
    private fun RunnerRegistrationOutcome.answer(created: NewRunner): NewRunner = when (this) {
        RunnerRegistrationOutcome.REGISTERED -> created
        RunnerRegistrationOutcome.NAME_TAKEN -> throw RunnerNameTakenException()
        RunnerRegistrationOutcome.LIMIT_REACHED -> throw RunnerLimitException(MAX_RUNNERS)
    }

    /** Constants of the enclosing type. */
    companion object {
        /** The most runners an organization may register; the list is never cut below it. */
        const val MAX_RUNNERS: Int = 100

        /** The longest name. */
        private const val MAX_NAME = 64

        /** The most labels of a runner. */
        private const val MAX_LABELS = 16

        private val LABEL = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")
        private val VERSION = Regex("[A-Za-z0-9][A-Za-z0-9.+_-]{0,63}")
    }
}
