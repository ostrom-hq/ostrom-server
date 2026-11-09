// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gates

import run.ostrom.server.gates.domain.models.CiCheck
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.models.GateNode
import run.ostrom.server.gates.domain.models.GatePullRequest
import run.ostrom.server.gates.domain.models.GateSubject
import run.ostrom.server.gates.domain.services.CiChecks
import run.ostrom.server.gates.domain.services.GateActorResolver
import run.ostrom.server.gates.domain.services.GateCheckNeed
import run.ostrom.server.gates.domain.services.GateCheckNeeds
import run.ostrom.server.gates.domain.services.GateCheckProof
import run.ostrom.server.gates.domain.services.GateCheckProofs
import run.ostrom.server.gates.domain.services.GateGitHubAccounts
import run.ostrom.server.gates.domain.services.GateNodeResolver
import run.ostrom.server.gates.domain.services.GateNotifier
import run.ostrom.server.gates.domain.services.GatePolicies
import run.ostrom.server.gates.domain.services.GateReceiptSigner
import run.ostrom.server.gates.domain.services.GateSessions
import run.ostrom.server.gates.domain.services.PullRequestHeads
import run.ostrom.server.gates.fakes.FakeGitHubAccounts
import run.ostrom.server.gates.fakes.FakeNotifier
import run.ostrom.server.gates.fakes.FakePolicies
import run.ostrom.server.gates.fakes.FakeSessions
import run.ostrom.server.gates.fakes.FakeSigner
import run.ostrom.server.gates.infrastructure.DefaultGatePolicies
import run.ostrom.server.gates.infrastructure.NoCiChecks
import run.ostrom.server.gates.infrastructure.NoGateActors
import run.ostrom.server.gates.infrastructure.NoGateCheckNeeds
import run.ostrom.server.gates.infrastructure.NoGateCheckProofs
import run.ostrom.server.gates.infrastructure.NoGateGitHubAccounts
import run.ostrom.server.gates.infrastructure.NoGateNodes
import run.ostrom.server.gates.infrastructure.UnavailableGateSessions
import run.ostrom.server.gates.infrastructure.UnknownPullRequestHeads
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.uuid.Uuid

class GatesModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("gates", GatesModule.name)
        koinApplication { modules(GatesModule.koinModule) }.close()
    }

    @Test
    fun withoutAnotherModuleThePortsFailClosed() = runTest {
        val app = koinApplication { }
        app.koin.loadModules(listOf(GatesModule.defaultPorts(app.koin)))
        assertIs<DefaultGatePolicies>(app.koin.get<GatePolicies>())
        assertIs<UnavailableGateSessions>(app.koin.get<GateSessions>())
        assertIs<NoGateNodes>(app.koin.get<GateNodeResolver>())
        assertIs<UnknownPullRequestHeads>(app.koin.get<PullRequestHeads>())
        assertIs<NoGateActors>(app.koin.get<GateActorResolver>())
        // Without integrations-github, nobody linked a GitHub account.
        val gitHub = assertIs<NoGateGitHubAccounts>(app.koin.get<GateGitHubAccounts>())
        assertEquals(emptyList(), gitHub.gitHubIdsOf("lyes"))
        val ci = assertIs<NoCiChecks>(app.koin.get<CiChecks>())
        // No forge, no proof: the check node stays the workstation's declaration.
        assertNull(ci.latest(Uuid.random(), "o/r", "0123456789abcdef0123456789abcdef01234567", "test"))
        val node = GateNode("ship", "g", "Ship", listOf("lyes"), enforced = true)
        val needs = assertIs<NoGateCheckNeeds>(app.koin.get<GateCheckNeeds>())
        assertEquals(emptyList<GateCheckNeed>(), needs.of(Uuid.random(), node))
        // No runs known, no proof: a check a gate needs attested is never proven.
        val subject = GateSubject(repo = "o/r", actionClass = "push-main", command = "git push", scope = "s")
        assertEquals(
            GateCheckProof(attestation = null, passed = false),
            assertIs<NoGateCheckProofs>(app.koin.get<GateCheckProofs>()).of(Uuid.random(), "s", subject, "tests"),
        )
        app.close()
    }

    @Test
    fun theDefaultsNeverReplaceAPortAnotherModuleBound() {
        val policies = FakePolicies()
        val sessions = FakeSessions()
        val nodes = object : GateNodeResolver {
            override suspend fun resolve(orgId: Uuid, repo: String?, actionClass: String): GateNode? = null
        }
        val heads = object : PullRequestHeads {
            override suspend fun head(orgId: Uuid, repo: String, number: Int): GatePullRequest? = null
        }
        val ci = object : CiChecks {
            override suspend fun latest(orgId: Uuid, repo: String, sha: String, name: String): CiCheck? = null
        }
        val needs = object : GateCheckNeeds {
            override suspend fun of(orgId: Uuid, node: GateNode): List<GateCheckNeed> = emptyList()
        }
        val proofs = object : GateCheckProofs {
            override suspend fun of(orgId: Uuid, sessionId: String?, subject: GateSubject, check: String) =
                GateCheckProof(attestation = null, passed = false)
        }
        val signer = FakeSigner()
        val notifier = FakeNotifier()
        val actors = object : GateActorResolver {
            override suspend fun resolve(principal: AuthenticatedPrincipal): GateActor? = null
        }
        val gitHub = FakeGitHubAccounts()
        val app = koinApplication {
            modules(
                module {
                    single<GateGitHubAccounts> { gitHub }
                    single<GatePolicies> { policies }
                    single<GateSessions> { sessions }
                    single<GateNodeResolver> { nodes }
                    single<PullRequestHeads> { heads }
                    single<CiChecks> { ci }
                    single<GateCheckNeeds> { needs }
                    single<GateCheckProofs> { proofs }
                    single<GateReceiptSigner> { signer }
                    single<GateNotifier> { notifier }
                    single<GateActorResolver> { actors }
                },
            )
        }
        app.koin.loadModules(listOf(GatesModule.defaultPorts(app.koin)))
        assertSame(policies, app.koin.get<GatePolicies>())
        assertSame(sessions, app.koin.get<GateSessions>())
        assertSame(nodes, app.koin.get<GateNodeResolver>())
        assertSame(heads, app.koin.get<PullRequestHeads>())
        assertSame(ci, app.koin.get<CiChecks>())
        assertSame(needs, app.koin.get<GateCheckNeeds>())
        assertSame(proofs, app.koin.get<GateCheckProofs>())
        assertSame(signer, app.koin.get<GateReceiptSigner>())
        assertSame(notifier, app.koin.get<GateNotifier>())
        assertSame(actors, app.koin.get<GateActorResolver>())
        assertSame(gitHub, app.koin.get<GateGitHubAccounts>())
        app.close()
    }
}
