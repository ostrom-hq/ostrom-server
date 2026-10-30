package dev.ostrom.server.gates

import dev.ostrom.server.gates.domain.models.AskedVia
import dev.ostrom.server.gates.domain.models.Evidence
import dev.ostrom.server.gates.domain.models.GateCommands
import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.models.GatePullRequest
import dev.ostrom.server.gates.domain.models.GatePush
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.OutdatedLauncherException
import dev.ostrom.server.gates.domain.models.PushRef
import dev.ostrom.server.gates.domain.models.RedirectedPushException
import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.gates.domain.services.GateNodeResolver
import dev.ostrom.server.gates.domain.services.PullRequestHeads
import dev.ostrom.server.gates.domain.usecases.AskGate
import dev.ostrom.server.gates.domain.usecases.AskGateUseCaseImpl
import dev.ostrom.server.gates.fakes.GateBench
import dev.ostrom.server.gates.fakes.IdentityDigester
import dev.ostrom.server.gates.fakes.SESSION
import dev.ostrom.server.gates.fakes.SHA_A
import dev.ostrom.server.gates.fakes.SHA_B
import dev.ostrom.server.gates.fakes.agent
import dev.ostrom.server.gates.fakes.device
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AskGateUseCaseTest {
    private val node = GateNode("ship", "g-main", "Merge into main", listOf("lyes", "rev-b"), enforced = true, workflowName = "Ship")
    private val resolved = mutableListOf<String?>()

    private fun ask(bench: GateBench, heads: Map<Int, GatePullRequest> = emptyMap(), gate: GateNode? = node) =
        AskGateUseCaseImpl(
            bench.open,
            object : GateNodeResolver {
                override suspend fun resolve(orgId: Uuid, repo: String?, actionClass: String): GateNode? {
                    resolved += repo
                    return gate
                }
            },
            object : PullRequestHeads {
                override suspend fun head(orgId: Uuid, repo: String, number: Int) = heads[number]
            },
            IdentityDigester(),
        )

    private fun pushAsk(
        diff: String? = "+line",
        realRepo: String? = null,
        force: Boolean = false,
        command: String = "git push origin main",
        remote: String = "git@github.com:o/r.git",
    ) = AskGate(
        sessionId = SESSION,
        runId = null,
        repo = " ostrom-hq/gate-demo ",
        realRepo = realRepo,
        actionClass = "push-main",
        tool = null,
        command = command,
        harnessVersion = null,
        push = GatePush(remote, listOf(PushRef("refs/heads/main", SHA_B), PushRef("refs/heads/old", "")), force),
        pullRequest = null,
        diff = diff,
        via = AskedVia.HOOK,
    )

    @Test
    fun aPushShowsItsCommitsDiffAndRepository() = runTest {
        val bench = GateBench()
        val view = ask(bench)(agent().copy(personId = "lyes"), pushAsk(force = true).copy(harnessVersion = "0.9.0", runId = "run-1"))

        assertEquals("Merge into main", view.gate.label)
        assertEquals("ostrom-hq/gate-demo", view.gate.subject.repo)
        assertEquals("+line", view.gate.subject.diffDigest)
        assertEquals(listOf(SHA_B), view.gate.subject.commits, "A deleted ref sends no commit")
        assertEquals("Bash", view.gate.subject.tool)
        assertEquals("run-1", view.gate.subject.runId)
        assertEquals(listOf("lyes"), view.gate.askedBy)
        assertEquals(
            listOf(
                Evidence("Gate", "Merge into main (Ship)"),
                Evidence("Command", "git push origin main"),
                Evidence(
                    "Commits",
                    "refs/heads/main ← 0123456789ab, refs/heads/old ← (deleted) on git@github.com:o/r.git (force-push). " +
                        "This yes covers these commits only.",
                ),
                Evidence("Repository", "ostrom-hq/gate-demo"),
                Evidence("Asked by", "lyes · session 00000000"),
                Evidence("Harness", "0.9.0"),
                Evidence("Diff", "+line"),
            ),
            view.gate.evidence,
        )
    }

    @Test
    fun aCommandRunningInAnotherRepositoryIsSaidSo() = runTest {
        val bench = GateBench()
        val view = ask(bench)(agent(), pushAsk(diff = null, realRepo = "evil/fork", remote = ""))

        assertEquals("evil/fork", view.gate.subject.realRepo)
        assertEquals(listOf("evil/fork"), resolved, "The gate of the repository the command really runs in")
        assertEquals("", view.gate.subject.diffDigest, "No diff sent: the empty diff is what was shown")
        assertEquals(
            listOf(
                Evidence("Gate", "Merge into main (Ship)"),
                Evidence("Command", "git push origin main"),
                Evidence(
                    "Commits",
                    "refs/heads/main ← 0123456789ab, refs/heads/old ← (deleted). This yes covers these commits only.",
                ),
                Evidence("Repository", "evil/fork"),
                Evidence("Session repository", "ostrom-hq/gate-demo: NOT the repository this command runs in (evil/fork). Check before saying yes."),
                Evidence("Asked by", "agent-$SESSION · session 00000000"),
            ),
            view.gate.evidence,
        )
    }

    @Test
    fun theSameRepositoryTwiceIsNotAnotherRepository() = runTest {
        val bench = GateBench()
        assertNull(ask(bench)(agent(), pushAsk(realRepo = "ostrom-hq/gate-demo")).gate.subject.realRepo)
        assertNull(ask(bench)(agent(), pushAsk(realRepo = "  ")).gate.subject.realRepo)
    }

    @Test
    fun aMergeTakesItsHeadAndBaseFromGitHub() = runTest {
        val bench = GateBench()
        val merge = pushAsk().copy(actionClass = "merge", command = "gh pr merge 6", pullRequest = 6, diff = null, tool = "mcp")
        val view = ask(bench, mapOf(6 to GatePullRequest(6, SHA_A, "main")))(agent(), merge)

        assertEquals(GatePullRequest(6, SHA_A, "main"), view.gate.subject.pullRequest)
        assertNull(view.gate.subject.push, "A merge is bound to the pull request, not to a push the caller describes")
        assertEquals(listOf(SHA_A), view.gate.subject.commits)
        assertNull(view.gate.subject.diffDigest)
        assertEquals("mcp", view.gate.subject.tool)
        assertEquals(Evidence("Asked by", "agent-$SESSION · session 00000000 · PR #6 @ aaaaaaa into main"), view.gate.evidence[3])

        val unknown = ask(bench)(agent(), merge.copy(pullRequest = 7, command = "gh pr merge 7", push = null))
        assertNull(unknown.gate.subject.pullRequest)
        val outside = ask(bench, mapOf(6 to GatePullRequest(6, SHA_A, "main")))(agent(), merge.copy(repo = "  ", push = null))
        assertNull(outside.gate.subject.pullRequest, "Outside a repository, GitHub is not asked")
        assertEquals(Evidence("Asked by", "agent-$SESSION · session 00000000"), outside.gate.evidence[2])
    }

    @Test
    fun anUngatedActionIsLabelledByItsClass() = runTest {
        val bench = GateBench()
        val view = ask(bench, gate = null)(device(), pushAsk().copy(command = "", repo = null, push = null, sessionId = null))
        assertEquals("push-main", view.gate.label)
        assertEquals(GateStatus.UNGATED, view.gate.status)
        assertEquals("session:-", view.gate.subject.scope)
        assertEquals(listOf(Evidence("Command", "Bash"), Evidence("Asked by", "device-lyes")), view.gate.evidence)
    }

    @Test
    fun anOldLauncherPushingWithoutItsCommitsIsToldToUpdate() = runTest {
        val bench = GateBench()
        assertFailsWith<OutdatedLauncherException> { ask(bench)(agent(), pushAsk().copy(push = null)) }
        assertTrue(bench.repository.gates.isEmpty())
        val shadow = node.copy(enforced = false)
        assertEquals(GateStatus.SHADOW, ask(bench, gate = shadow)(agent(), pushAsk().copy(push = null)).gate.status)
        assertEquals(
            GateStatus.PENDING,
            ask(bench)(agent(), pushAsk().copy(push = null, command = "kubectl apply -f prod.yaml")).gate.status,
        )
    }

    @Test
    fun aRedirectedPushIsReadFromTheCommandNotFromTheHook() = runTest {
        val bench = GateBench()
        for (command in listOf("GIT_DIR=/tmp/x git push origin main", "git -c remote.origin.url=evil push origin main")) {
            assertFailsWith<RedirectedPushException> { ask(bench)(agent(), pushAsk(command = command)) }
        }
        assertTrue(bench.repository.gates.isEmpty())
    }

    @Test
    fun theCommandRulesMatchThePrototype() {
        assertTrue(GateCommands.redirected("git --git-dir=/x push"))
        assertTrue(GateCommands.redirected("git --work-tree /x push"))
        assertTrue(GateCommands.redirected("GIT_CONFIG_COUNT=1 git push"))
        assertTrue(GateCommands.redirected("git -c url.evil.insteadOf=git@github.com: push"))
        assertTrue(GateCommands.redirected("git -c 'core.sshCommand=x' push"))
        assertFalse(GateCommands.redirected("git push origin main"))
        assertFalse(GateCommands.redirected("git commit -c HEAD"))
        assertTrue(GateCommands.mentionsPush("cd x && git -C y push origin main"))
        assertFalse(GateCommands.mentionsPush("git commit -m done; echo push"))
    }

    @Test
    fun theDefaultPortsFailClosed() = runTest {
        val gate = GateBench().open(GateBench().pushRequest()).gate
        assertEquals(SelfApprovalPolicy.SOLE_APPROVER, DefaultGatePolicies().selfApproval(gate.orgId))
        val sessions = assertFailsWith<OstromException> { UnavailableGateSessions().assertOpen(gate.orgId, SESSION) }
        assertEquals(ErrorKind.UNAVAILABLE, sessions.kind)
        assertNull(NoGateNodes().resolve(gate.orgId, "o/r", "push-main"))
        assertNull(UnknownPullRequestHeads().head(gate.orgId, "o/r", 6))
        assertEquals(ErrorKind.UNAVAILABLE, assertFailsWith<OstromException> { UnavailableGateReceiptSigner().sign(gate) }.kind)
        SilentGateNotifier().requested(gate, emptyList())
        SilentGateNotifier().decided(gate, emptyList())
        assertNull(NoGateActors().resolve(AuthenticatedPrincipal("x", PrincipalKind.USER, emptySet())))
    }
}
