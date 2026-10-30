package dev.ostrom.server.gates

import dev.ostrom.server.gates.domain.models.AskedVia
import dev.ostrom.server.gates.domain.models.Evidence
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.models.GatePullRequest
import dev.ostrom.server.gates.domain.models.GatePush
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.PushRef
import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.gates.domain.services.GateNodeResolver
import dev.ostrom.server.gates.domain.services.PullRequestHeads
import dev.ostrom.server.gates.domain.usecases.AskGate
import dev.ostrom.server.gates.domain.usecases.AskGateUseCaseImpl
import dev.ostrom.server.gates.fakes.GateBench
import dev.ostrom.server.gates.fakes.IdentityDigester
import dev.ostrom.server.gates.fakes.SESSION
import dev.ostrom.server.gates.fakes.SHA_A
import dev.ostrom.server.gates.fakes.agent
import dev.ostrom.server.gates.fakes.user
import dev.ostrom.server.gates.infrastructure.DefaultGatePolicies
import dev.ostrom.server.gates.infrastructure.NoGateActors
import dev.ostrom.server.gates.infrastructure.NoGateNodes
import dev.ostrom.server.gates.infrastructure.SilentGateNotifier
import dev.ostrom.server.gates.infrastructure.UnavailableGateReceiptSigner
import dev.ostrom.server.gates.infrastructure.UnavailableGateSessions
import dev.ostrom.server.gates.infrastructure.UnknownPullRequestHeads
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class AskGateUseCaseTest {
    private val node = GateNode("ship", "g-main", "Merge into main", listOf("lyes", "rev-b"), enforced = true)

    private fun ask(bench: GateBench, heads: Map<Int, GatePullRequest> = emptyMap(), gated: Boolean = true) =
        AskGateUseCaseImpl(
            bench.open,
            object : GateNodeResolver {
                override suspend fun resolve(orgId: Uuid, repo: String?, actionClass: String) = node.takeIf { gated }
            },
            object : PullRequestHeads {
                override suspend fun head(orgId: Uuid, repo: String, number: Int) = heads[number]
            },
            IdentityDigester(),
        )

    private fun pushAsk(diff: String? = "+line", realRepo: String? = null, force: Boolean = false) = AskGate(
        sessionId = SESSION,
        repo = " ostrom-hq/gate-demo ",
        realRepo = realRepo,
        actionClass = "push-main",
        command = "git push origin main",
        push = GatePush("remote", listOf(PushRef("refs/heads/main", SHA_A)), force),
        pullRequest = null,
        commits = listOf(SHA_A),
        diff = diff,
        redirected = false,
        via = AskedVia.HOOK,
    )

    @Test
    fun aPushShowsItsCommitsDiffAndRepository() = runTest {
        val bench = GateBench()
        val view = ask(bench)(agent().copy(personId = "lyes"), pushAsk(force = true))

        assertEquals("Merge into main", view.gate.label)
        assertEquals("ostrom-hq/gate-demo", view.gate.subject.repo)
        assertEquals("+line", view.gate.subject.diffDigest)
        assertEquals(listOf("lyes"), view.gate.askedBy)
        assertEquals(
            listOf(
                Evidence("Gate", "Merge into main"),
                Evidence("Command", "git push origin main"),
                Evidence("Repository", "ostrom-hq/gate-demo"),
                Evidence("Commits", "refs/heads/main ← aaaaaaa (force-push). This yes covers these commits only."),
                Evidence("Diff", "+line"),
            ),
            view.gate.evidence,
        )
    }

    @Test
    fun aCommandRunningInAnotherRepositoryIsSaidSo() = runTest {
        val bench = GateBench()
        val view = ask(bench)(agent(), pushAsk(diff = null, realRepo = "evil/fork"))

        assertEquals("evil/fork", view.gate.subject.realRepo)
        assertEquals("", view.gate.subject.diffDigest, "No diff sent: the empty diff is what was shown")
        assertEquals(Evidence("Repository", "evil/fork"), view.gate.evidence[2])
        assertEquals(
            Evidence("Session repository", "ostrom-hq/gate-demo: NOT the repository this command runs in. Check before saying yes."),
            view.gate.evidence[3],
        )
        assertEquals("agent-$SESSION", view.gate.askedBy.single(), "Without a person, the token is the asker")
    }

    @Test
    fun theSameRepositoryTwiceIsNotAnotherRepository() = runTest {
        val bench = GateBench()
        val view = ask(bench)(agent(), pushAsk(realRepo = "ostrom-hq/gate-demo"))
        assertNull(view.gate.subject.realRepo)
        assertNull(ask(bench)(agent(), pushAsk(realRepo = "  ")).gate.subject.realRepo)
    }

    @Test
    fun aMergeTakesItsHeadAndBaseFromGitHub() = runTest {
        val bench = GateBench()
        val merge = pushAsk().copy(actionClass = "merge", command = "gh pr merge 6", push = null, pullRequest = 6, diff = null)
        val view = ask(bench, mapOf(6 to GatePullRequest(6, SHA_A, "main")))(agent(), merge)

        assertEquals(GatePullRequest(6, SHA_A, "main"), view.gate.subject.pullRequest)
        assertEquals(listOf(SHA_A), view.gate.subject.commits)
        assertNull(view.gate.subject.diffDigest)
        assertEquals(Evidence("Pull request", "#6 @ aaaaaaa into main"), view.gate.evidence[3])

        val unknown = ask(bench)(agent(), merge.copy(pullRequest = 7, command = "gh pr merge 7"))
        assertNull(unknown.gate.subject.pullRequest)
        val outside = ask(bench, mapOf(6 to GatePullRequest(6, SHA_A, "main")))(agent(), merge.copy(repo = "  "))
        assertNull(outside.gate.subject.pullRequest, "Outside a repository, GitHub is not asked")
        assertEquals(Evidence("Repository", "none"), outside.gate.evidence[2])
    }

    @Test
    fun anUngatedActionIsLabelledByItsClass() = runTest {
        val bench = GateBench()
        val view = ask(bench, gated = false)(agent(), pushAsk().copy(command = "", repo = null))
        assertEquals("push-main", view.gate.label)
        assertEquals(GateStatus.UNGATED, view.gate.status)
        assertEquals(Evidence("Repository", "none"), view.gate.evidence.first())
    }

    @Test
    fun theDefaultPortsFailClosed() = runTest {
        val gate: Gate = GateBench().open(GateBench().pushRequest()).gate
        assertEquals(SelfApprovalPolicy.SOLE_APPROVER, DefaultGatePolicies().selfApproval(gate.orgId))
        val sessions = assertFailsWith<OstromException> { UnavailableGateSessions().assertOpen(gate.orgId, SESSION) }
        assertEquals(ErrorKind.UNAVAILABLE, sessions.kind)
        assertNull(NoGateNodes().resolve(gate.orgId, "o/r", "push-main"))
        assertNull(UnknownPullRequestHeads().head(gate.orgId, "o/r", 6))
        assertEquals(ErrorKind.UNAVAILABLE, assertFailsWith<OstromException> { UnavailableGateReceiptSigner().sign(gate) }.kind)
        SilentGateNotifier().requested(gate, emptyList())
        SilentGateNotifier().decided(gate, emptyList())
        assertNull(NoGateActors().resolve(AuthenticatedPrincipal("x", PrincipalKind.USER)))
        assertEquals(user("x").kind, dev.ostrom.server.gates.domain.models.ActorKind.USER)
    }
}
