// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns

import run.ostrom.server.automations.AutomationsModule
import run.ostrom.server.compiler.CompilerModule
import run.ostrom.server.gates.GatesModule
import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.launches.LaunchesModule
import run.ostrom.server.launches.domain.models.Launcher
import run.ostrom.server.launches.domain.services.LauncherResolver
import run.ostrom.server.orgs.OrgsModule
import run.ostrom.server.receipts.ReceiptsModule
import run.ostrom.server.runners.RunnersModule
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.CloudSessionLimitException
import run.ostrom.server.runners.domain.CreateCloudSessionPayload
import run.ostrom.server.runners.domain.CreateCloudSessionUseCase
import run.ostrom.server.runners.domain.KeyedCreation
import run.ostrom.server.runners.domain.ProfileUnassignedException
import run.ostrom.server.sessions.SessionsModule
import run.ostrom.server.testing.serverTest
import run.ostrom.server.workflowruns.domain.engine.profileRefused
import run.ostrom.server.workflowruns.domain.services.AgentRequest
import run.ostrom.server.workflowruns.infrastructure.CloudRunAgents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** An agent node's cloud session runs on the profile its request names (TeamCode's workflow `profileId`). */
class RunAgentsProfilePostgresTest {
    private val modules = listOf(
        InstanceModule, IdentityModule, OrgsModule, ReceiptsModule, GatesModule, SessionsModule, CompilerModule,
        RunnersModule, LaunchesModule, AutomationsModule, WorkflowRunsModule,
    )
    private val org = Uuid.random()
    private val user = Uuid.random()

    // The starter "ada" is a member of the organization, whatever the database holds.
    private val members = object : LauncherResolver {
        override suspend fun resolve(principal: AuthenticatedPrincipal): Launcher? = null

        override suspend fun member(orgId: Uuid, userId: Uuid) = OrgPrincipal(orgId, userId, OrgRole.MEMBER)

        override suspend fun owner(orgId: Uuid, memberId: Uuid): Launcher? = null

        override suspend fun colleague(orgId: Uuid, login: String) = Launcher(orgId, user, login, OrgRole.MEMBER)
    }

    @Test
    fun aRefusalOfTheProfileKeepsItsKeyAndAnyOtherItsSentence() = serverTest(modules) { server ->
        val asked = mutableListOf<CreateCloudSessionPayload>()
        var refusal: OstromException = ProfileUnassignedException()
        val refusing = object : CreateCloudSessionUseCase {
            override suspend fun invoke(caller: Caller, payload: CreateCloudSessionPayload): CloudSession {
                asked += payload
                throw refusal
            }

            override suspend fun createKeyed(
                caller: Caller,
                payload: CreateCloudSessionPayload,
                key: String,
                requestDigest: String,
            ): KeyedCreation = error("A run's session is created unkeyed.")
        }
        val agents = CloudRunAgents(refusing, server.koin.get(), members, Clock.System)
        val profile = Uuid.random()
        val request = AgentRequest(Uuid.random(), "ada", "Ship · Build", "claude", null, "Build", profile)
        assertEquals(profileRefused("profile_unassigned"), agents.start(org, request))
        refusal = CloudSessionLimitException(1)
        assertEquals(refusal.message, agents.start(org, request))
        assertEquals(listOf<Uuid?>(profile, profile), asked.map { it.profileId })
    }
}
