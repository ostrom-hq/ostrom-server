// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.domain.usecases

import run.ostrom.core.api.models.CheckAttestation
import run.ostrom.core.api.models.CheckOutcome
import run.ostrom.core.api.models.CheckRun
import run.ostrom.core.api.models.PackageMode
import run.ostrom.core.api.models.ReportCheckRequest
import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.kernel.errors.Pages
import run.ostrom.server.receipts.domain.usecases.isoMillis
import run.ostrom.server.sessions.domain.models.SessionAccess
import run.ostrom.server.workflowruns.domain.models.InvalidRunException
import run.ostrom.server.workflowruns.domain.models.NotInPackageException
import run.ostrom.server.workflowruns.domain.repositories.RunQuery
import run.ostrom.server.workflowruns.domain.repositories.WorkflowRunsRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Records a check run the launcher's hook reports (contract `reportCheck`, prototype `POST /api/compiler/runs`). */
interface ReportCheckUseCase {
    /**
     * Records the run.
     *
     * @param actor The caller: the agent token of the session's launch, or the session's person.
     * @param report What the hook ran.
     *
     * @return The run as stored: its mode and version are the package's, its person the session's.
     *
     * @throws run.ostrom.server.workflowruns.domain.models.RunPackageNotFoundException When the package is unknown.
     * @throws NotInPackageException When the package has no such script for that node.
     */
    suspend operator fun invoke(actor: GateActor, report: ReportCheckRequest): CheckRun

    /**
     * Records a run the server holds a proof of: the same checks as [invoke], with where the run comes from. The
     * proof holds only when the run's script is the package's (its `scriptSha`): any other run is the workstation's.
     *
     * @param actor The session's agent, as the runner of its cloud session reports for it ([ReportRunnerCheckUseCase]).
     * @param report What was run.
     * @param attestation Where the run comes from.
     *
     * @return The run as stored.
     */
    suspend fun attested(actor: GateActor, report: ReportCheckRequest, attestation: CheckAttestation): CheckRun
}

/**
 * [ReportCheckUseCase] on the repository.
 *
 * @param access Who may write for a session.
 * @param repository The runs.
 * @param clock The clock.
 */
class ReportCheckUseCaseImpl(
    private val access: RunAccess,
    private val repository: WorkflowRunsRepository,
    private val clock: Clock,
) : ReportCheckUseCase {
    override suspend fun invoke(actor: GateActor, report: ReportCheckRequest): CheckRun =
        attested(actor, report, CheckAttestation.WORKSTATION_DECLARED)

    override suspend fun attested(
        actor: GateActor,
        report: ReportCheckRequest,
        attestation: CheckAttestation,
    ): CheckRun {
        RunTexts.bounded("sessionId", report.sessionId)
        RunTexts.bounded("head", report.head)
        RunTexts.bounded("sandbox", report.sandbox)
        val session = access.writing(actor, report.sessionId)
        val pack = access.pack(actor, report.digest, session)
        val script = pack.scripts.firstOrNull { it.id == report.scriptId && it.nodeId == report.nodeId }
            ?: throw NotInPackageException("Script ${report.scriptId.take(RunTexts.ID)}")
        // The receipt reads the exit code: an outcome that contradicts it is refused, never guessed.
        if (!consistent(report, script.mode)) {
            throw InvalidRunException("outcome: ${report.outcome.value.ifEmpty { "unknown" }} contradicts exit.")
        }
        val duration = report.durationMs
        if (duration != null && duration < 0) throw InvalidRunException("durationMs: zero or more.")
        val run = CheckRun(
            id = "run_" + Uuid.random().toHexString(),
            at = clock.now().isoMillis(),
            sessionId = session?.id,
            digest = pack.digest,
            version = pack.version,
            nodeId = script.nodeId,
            scriptId = script.id,
            scriptSha = report.scriptSha?.take(RunTexts.ID),
            // The launcher ran a script whose body is not the package's: the receipt says so.
            tampered = report.scriptSha != null && report.scriptSha != script.sha256,
            mode = script.mode,
            exit = report.exit,
            durationMs = duration,
            head = report.head,
            cached = report.cached,
            sandbox = report.sandbox,
            outputTail = report.outputTail?.let { RunTexts.masked(it).takeLast(RunTexts.OUTPUT) },
            dev = if (session == null) SessionAccess.person(actor) else session.dev,
            repo = session?.repo ?: pack.envelope.repo,
        )
        // A proof holds for the package's own script only: the runner runs the text the hook sends, so a run of any
        // other text, or of a text it did not hash, is no more than declared (AUDIT-SECU-RUNNER-2 R1).
        val proof = if (report.scriptSha == script.sha256) attestation else CheckAttestation.WORKSTATION_DECLARED
        repository.addCheck(actor.orgId, run, proof)
        return run
    }

    // As the launcher's hooks report: passed on exit 0; failed or timed out otherwise (not run: no exit); skipped
    // for a shadow script only, which runs nothing.
    private fun consistent(report: ReportCheckRequest, mode: PackageMode): Boolean = when (report.outcome) {
        CheckOutcome.PASSED -> report.exit == 0
        CheckOutcome.FAILED, CheckOutcome.TIMEOUT -> report.exit != 0
        CheckOutcome.SKIPPED -> report.exit == null && mode == PackageMode.SHADOW
        CheckOutcome.UNKNOWN -> false
    }
}

/** Lists the check runs (contract `listChecks`): the console's, and an agent's for its own session. */
interface ListChecksUseCase {
    /**
     * Lists the runs, newest first.
     *
     * @param actor The caller.
     * @param query Which; an agent token names its own session.
     *
     * @return The runs.
     *
     * @throws run.ostrom.server.workflowruns.domain.models.RunSessionNotFoundException When the session is not one the
     *   caller may read.
     */
    suspend operator fun invoke(actor: GateActor, query: RunQuery): List<CheckRun>
}

/**
 * [ListChecksUseCase] on the repository.
 *
 * @param access Who may read a session.
 * @param repository The runs.
 */
class ListChecksUseCaseImpl(
    private val access: RunAccess,
    private val repository: WorkflowRunsRepository,
) : ListChecksUseCase {
    override suspend fun invoke(actor: GateActor, query: RunQuery): List<CheckRun> {
        val sessionId = query.sessionId
        if (sessionId != null) access.reading(actor, sessionId)
        // An agent token reads its own session's runs only: without one, nothing.
        if (sessionId == null && actor.kind == ActorKind.AGENT_SESSION) return emptyList()
        // TeamCode #238: without a session, a member lists their own and those of the sessions whose gates they
        // approve. A `before` that names none the caller reads (forged, another organization's or member's) is a 400,
        // not an empty page: a member's before any page, whose start would otherwise order theirs against another's.
        val audience = if (sessionId == null) access.audience(actor) else null
        val before = query.before
        if (audience != null && before != null && !repository.hasCheck(actor.orgId, before, audience)) {
            Pages.unknownCursor("before")
        }
        val page = repository.checks(actor.orgId, query.copy(audience = audience))
        return Pages.checked("before", before, page) { repository.hasCheck(actor.orgId, it, audience) }
    }
}
