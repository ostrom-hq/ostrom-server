// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.fakes

import dev.ostrom.server.gates.domain.models.Answer
import dev.ostrom.server.gates.domain.models.AnswerChannel
import dev.ostrom.server.gates.domain.models.AskedVia
import dev.ostrom.server.gates.domain.models.Evidence
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.models.GatePullRequest
import dev.ostrom.server.gates.domain.models.GatePush
import dev.ostrom.server.gates.domain.models.GateSubject
import dev.ostrom.server.gates.domain.models.GateView
import dev.ostrom.server.gates.domain.models.PushRef
import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.gates.domain.usecases.AnswerGateUseCaseImpl
import dev.ostrom.server.gates.domain.usecases.EnsureGateReceiptUseCaseImpl
import dev.ostrom.server.gates.domain.usecases.GetApprovalQueueUseCaseImpl
import dev.ostrom.server.gates.domain.usecases.GetApprovalUseCaseImpl
import dev.ostrom.server.gates.domain.usecases.GetApprovalViewUseCaseImpl
import dev.ostrom.server.gates.domain.usecases.GetGateUseCaseImpl
import dev.ostrom.server.gates.domain.usecases.ListApprovalsUseCaseImpl
import dev.ostrom.server.gates.domain.usecases.OpenGate
import dev.ostrom.server.gates.domain.usecases.OpenGateUseCaseImpl
import dev.ostrom.server.gates.domain.usecases.UseGateUseCaseImpl
import dev.ostrom.server.gates.infrastructure.InProcessGateChanges

/** Every gate use case wired on the fakes, with helpers that build the requests of the prototype's tests. */
class GateBench {
    val repository = InMemoryGatesRepository()
    val sessions = FakeSessions()
    // The prototype's tests model an organization that chose `sole-approver`; one that never chose is `never`.
    val policies = FakePolicies(SelfApprovalPolicy.SOLE_APPROVER)
    val gitHub = FakeGitHubAccounts()
    val notifier = FakeNotifier()
    val signer = FakeSigner()
    val clock = SettableClock()
    val logger = RecordingLogger()
    val receipts = EnsureGateReceiptUseCaseImpl(repository, signer, logger)
    val needs = FakeCheckNeeds()
    val proofs = FakeCheckProofs()
    val open = OpenGateUseCaseImpl(repository, sessions, policies, notifier, IdentityDigester(), clock, needs, proofs)
    val changes = InProcessGateChanges()
    val unlocks = FakePhoneUnlocks(clock)
    val answer = AnswerGateUseCaseImpl(repository, policies, receipts, notifier, clock, changes, gitHub, unlocks)
    val use = UseGateUseCaseImpl(repository, receipts, clock, sessions)
    val get = GetGateUseCaseImpl(repository, policies, receipts, changes)
    val approvals = ListApprovalsUseCaseImpl(repository)
    val approval = GetApprovalUseCaseImpl(repository)
    val queue = GetApprovalQueueUseCaseImpl(repository, policies, gitHub)
    val view = GetApprovalViewUseCaseImpl(repository, policies, gitHub)

    /** The gate node "Merge into main" of the workflow `ship`. */
    fun node(vararg approvers: String, enforced: Boolean = true) =
        GateNode("ship", "g-main", "Merge into main", approvers.toList(), enforced)

    /** A push of [sha] to main from the session [session]. */
    @Suppress("LongParameterList") // A test fixture: one named argument with a default per field.
    fun pushRequest(
        sha: String = SHA_A,
        session: String = SESSION,
        asker: String = "lyes",
        node: GateNode? = node("lyes", "rev-b"),
        force: Boolean = false,
        diff: String = "diff-1",
        command: String = "git push origin main",
        redirected: Boolean = false,
    ) = OpenGate(
        actor = agent(session),
        asker = asker,
        via = AskedVia.HOOK,
        sessionId = session,
        subject = GateSubject(
            repo = "ostrom-hq/gate-demo",
            actionClass = "push-main",
            command = command,
            scope = "session:$session",
            push = GatePush("git@github.com:ostrom-hq/gate-demo.git", listOf(PushRef("refs/heads/main", sha)), force),
            commits = listOf(sha),
            diffDigest = diff,
        ),
        node = node,
        label = "Push to main",
        evidence = listOf(Evidence("Repository", "ostrom-hq/gate-demo")),
        redirected = redirected,
    )

    /** The merge of pull request #6 at [sha] into [base], asked by the hook of [session] or by the GitHub check. */
    @Suppress("LongParameterList") // A test fixture: one named argument with a default per field.
    fun mergeRequest(
        sha: String = SHA_A,
        base: String = "main",
        viaCheck: Boolean = false,
        session: String = SESSION,
        asker: String = "lyes",
        node: GateNode? = node("lyes", "rev-b"),
    ) = OpenGate(
        actor = if (viaCheck) ciService() else agent(session),
        asker = asker,
        via = if (viaCheck) AskedVia.CI else AskedVia.HOOK,
        sessionId = if (viaCheck) null else session,
        subject = GateSubject(
            repo = "ostrom-hq/gate-demo",
            actionClass = if (viaCheck) "push-main" else "merge",
            command = if (viaCheck) "" else "gh pr merge 6 --merge",
            scope = if (viaCheck) "sha:$sha" else "session:$session",
            pullRequest = GatePullRequest(6, sha, base),
            commits = listOf(sha),
        ),
        node = node,
        label = "Merge pull request #6",
        evidence = listOf(Evidence("Pull request", "#6")),
    )

    /** [approver] answers their request of [view]'s gate, having seen what the gate shows. */
    suspend fun answer(
        view: GateView,
        approver: String,
        answer: Answer = Answer.YES,
        actor: GateActor = user(approver),
    ) = this.answer(
        actor.copy(authenticatedAt = actor.authenticatedAt ?: clock.now()),
        view.requests.first { it.approverId.equals(approver, ignoreCase = true) }.id,
        answer,
        view.gate.shownDigest,
        AnswerChannel.MOBILE,
    )
}
