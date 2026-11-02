// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.governance.domain

import dev.ostrom.server.kernel.auth.GovernanceChange
import kotlin.time.Duration

/** Records each step of a confirmation in its organization's signed audit log. */
interface ConfirmationAudit {
    /**
     * Records one step.
     *
     * @param confirmation The confirmation, as it stands after the step.
     * @param step The step.
     * @param actorUserId Who made it: the requester, the person who answered, or null for the server (an expiry).
     *
     * @throws ConfirmationAuditUnavailableException When the server keeps no audit log.
     */
    suspend fun record(confirmation: Confirmation, step: ConfirmationStep, actorUserId: String?)
}

/** The [ConfirmationAudit] of a server without an audit log: nothing is recorded, so nothing is opened. */
class RefusingConfirmationAudit : ConfirmationAudit {
    override suspend fun record(confirmation: Confirmation, step: ConfirmationStep, actorUserId: String?): Unit =
        throw ConfirmationAuditUnavailableException()
}

/**
 * A person as a confirmation shows them.
 *
 * @property login Their login.
 * @property name Their name.
 */
data class PersonName(val login: String, val name: String)

/** Names the people of a confirmation. */
interface ConfirmationPeople {
    /**
     * Names a person.
     *
     * @param userId Their user id.
     *
     * @return Their name, or null when unknown.
     */
    suspend fun nameOf(userId: String): PersonName?
}

/** Wakes the reads waiting on a confirmation once it is answered on this instance (a shortcut, never the truth). */
interface ConfirmationWaits {
    /**
     * Waits for [id] to change on this instance, at most [within]; holds no database connection.
     *
     * @param id The confirmation.
     * @param within The longest wait.
     */
    suspend fun await(id: String, within: Duration)

    /**
     * Says [id] changed.
     *
     * @param id The confirmation.
     */
    fun changed(id: String)
}

/**
 * The governance writes of every module, by kind.
 *
 * @param changes The modules' [GovernanceChange]s.
 */
class GovernanceChanges(changes: List<GovernanceChange>) {
    private val byKind: Map<String, GovernanceChange> = buildMap {
        for (change in changes) {
            for (kind in change.kinds) {
                require(put(kind, change) == null) { "Two modules own the governance write $kind." }
            }
        }
    }

    /**
     * The module that owns [kind].
     *
     * @param kind A [dev.ostrom.server.kernel.auth.GovernanceWrite.kind].
     *
     * @return Its [GovernanceChange].
     *
     * @throws IllegalStateException When no module owns it: a route held a write no module makes.
     */
    fun of(kind: String): GovernanceChange =
        checkNotNull(byKind[kind]) { "No module makes the governance write $kind." }
}
