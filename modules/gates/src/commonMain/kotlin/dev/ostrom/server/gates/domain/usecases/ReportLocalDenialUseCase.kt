// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.domain.usecases

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.InvalidGateRequestException
import dev.ostrom.server.gates.domain.models.NotYourGateException
import dev.ostrom.server.gates.domain.services.GateSessions
import dev.ostrom.server.kernel.audit.OrgAuditRecord
import dev.ostrom.server.kernel.audit.OrgAuditTrail
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.text.SecretMasks

/**
 * A gated action the hook refused on the workstation without asking the server (audit A6): a push that redirects git
 * away from the repository the gate covers, or a push it cannot read.
 *
 * @property sessionId The session segment the agent ran in.
 * @property actionClass The action class of the gate.
 * @property reason `redirected` or `unreadable`.
 * @property command The command as the hook normalized it.
 * @property repo The repository of the session, or null.
 */
data class LocalDenial(
    val sessionId: String,
    val actionClass: String,
    val reason: String,
    val command: String,
    val repo: String?,
)

/** Writes a refusal the hook made on the workstation in the organization's audit log. */
interface ReportLocalDenialUseCase {
    /**
     * Records the refusal as `gate.denied_locally`. The refusal stands whatever this answers: the hook never waits on
     * it.
     *
     * @param actor The agent or person token that reports it.
     * @param by The credential, the actor of the audit line.
     * @param denial What was refused.
     * @throws InvalidGateRequestException When a field is out of bounds or the reason is unknown.
     * @throws NotYourGateException When an agent token reports outside a launch.
     */
    suspend operator fun invoke(actor: GateActor, by: AuthenticatedPrincipal, denial: LocalDenial)
}

/**
 * [ReportLocalDenialUseCase] on the sessions and the organization's audit log: the session is checked as a gate's
 * ask checks it (unknown 404, closed 409, another launch's refused, audit A12), the command is masked again whatever
 * the workstation removed, then one line is written in the organization's chain.
 *
 * @param sessions Guards closed sessions and other launches' sessions.
 * @param audit The organization's audit log.
 */
class ReportLocalDenialUseCaseImpl(
    private val sessions: GateSessions,
    private val audit: OrgAuditTrail,
) : ReportLocalDenialUseCase {
    override suspend fun invoke(actor: GateActor, by: AuthenticatedPrincipal, denial: LocalDenial) {
        // Red (TDD): accepts the report without checking the session nor writing the audit line.
        validate(denial)
    }

    private fun validate(denial: LocalDenial) {
        val session = denial.sessionId
        ensure(session.isNotBlank() && session.length <= MAX_SESSION_ID) {
            "A session id has 1 to $MAX_SESSION_ID characters."
        }
        val actionClass = denial.actionClass
        ensure(actionClass.isNotBlank() && actionClass.length <= MAX_CLASS) {
            "An action class has 1 to $MAX_CLASS characters."
        }
        ensure(denial.reason in REASONS) { "The reason is one of ${REASONS.joinToString()}." }
        ensure(denial.command.length <= MAX_COMMAND) { "The command is longer than $MAX_COMMAND characters." }
        ensure(denial.repo.orEmpty().length <= MAX_REPO) { "The repository is longer than $MAX_REPO characters." }
    }

    private inline fun ensure(condition: Boolean, reason: () -> String) {
        if (!condition) throw InvalidGateRequestException(reason())
    }

    /** The audit line and the bounds of what the hook sends (contract `GateLocalDenial`). */
    companion object {
        /** The event of the organization's audit log. */
        const val EVENT: String = "gate.denied_locally"

        /** What the line is about: the session. */
        const val SUBJECT: String = "session"

        /** The reasons a hook refuses locally. */
        val REASONS: Set<String> = setOf("redirected", "unreadable")

        /** The longest session id. */
        const val MAX_SESSION_ID: Int = 128

        /** The longest action class. */
        const val MAX_CLASS: Int = 200

        /** The longest command (contract `GateLocalDenial.command`). */
        const val MAX_COMMAND: Int = 4_096

        /** The longest repository. */
        const val MAX_REPO: Int = 200
    }
}
