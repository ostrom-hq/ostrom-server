// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates

import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.models.GatePullRequest
import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.gates.domain.services.GateNodeResolver
import dev.ostrom.server.gates.domain.services.GateNotifier
import dev.ostrom.server.gates.domain.services.GatePolicies
import dev.ostrom.server.gates.domain.services.GateReceiptSigner
import dev.ostrom.server.gates.domain.services.GateSessions
import dev.ostrom.server.gates.domain.services.PullRequestHeads
import dev.ostrom.server.gates.fakes.FakeNotifier
import dev.ostrom.server.gates.fakes.FakePolicies
import dev.ostrom.server.gates.fakes.FakeSessions
import dev.ostrom.server.gates.fakes.FakeSigner
import dev.ostrom.server.gates.infrastructure.DefaultGatePolicies
import dev.ostrom.server.gates.infrastructure.NoGateActors
import dev.ostrom.server.gates.infrastructure.NoGateNodes
import dev.ostrom.server.gates.infrastructure.UnavailableGateSessions
import dev.ostrom.server.gates.infrastructure.UnknownPullRequestHeads
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.uuid.Uuid

class GatesModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("gates", GatesModule.name)
        koinApplication { modules(GatesModule.koinModule) }.close()
    }

    @Test
    fun withoutAnotherModuleThePortsFailClosed() {
        val app = koinApplication { }
        app.koin.loadModules(listOf(GatesModule.defaultPorts(app.koin)))
        assertIs<DefaultGatePolicies>(app.koin.get<GatePolicies>())
        assertIs<UnavailableGateSessions>(app.koin.get<GateSessions>())
        assertIs<NoGateNodes>(app.koin.get<GateNodeResolver>())
        assertIs<UnknownPullRequestHeads>(app.koin.get<PullRequestHeads>())
        assertIs<NoGateActors>(app.koin.get<GateActorResolver>())
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
        val signer = FakeSigner()
        val notifier = FakeNotifier()
        val actors = object : GateActorResolver {
            override suspend fun resolve(principal: AuthenticatedPrincipal): GateActor? = null
        }
        val app = koinApplication {
            modules(
                module {
                    single<GatePolicies> { policies }
                    single<GateSessions> { sessions }
                    single<GateNodeResolver> { nodes }
                    single<PullRequestHeads> { heads }
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
        assertSame(signer, app.koin.get<GateReceiptSigner>())
        assertSame(notifier, app.koin.get<GateNotifier>())
        assertSame(actors, app.koin.get<GateActorResolver>())
        app.close()
    }
}
