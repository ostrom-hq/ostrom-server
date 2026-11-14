// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.storage.ArtifactObjectStore
import run.ostrom.server.artifacts.infrastructure.storage.S3ObjectStore
import run.ostrom.server.artifacts.fakes.DAVE
import run.ostrom.server.artifacts.fakes.ORG_A
import run.ostrom.server.artifacts.fakes.TestOrgResolver
import run.ostrom.server.artifacts.presentation.ArtifactOrgResolver
import run.ostrom.server.kernel.auth.AgentMember
import run.ostrom.server.kernel.auth.AgentMembers
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid

class ArtifactsModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("artifacts", ArtifactsModule.name)
        koinApplication { modules(ArtifactsModule.koinModule) }.close()
    }

    @Test
    fun anAgentTokenIsReadWithTheBoundAgentMembers() = runTest {
        val dave = AgentMember(OrgPrincipal(ORG_A, DAVE, OrgRole.MEMBER), "launch-1")
        val launches = object : AgentMembers {
            override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?) = dave
        }
        val application = koinApplication {
            modules(
                module {
                    single<PrincipalOrgResolver> { TestOrgResolver }
                    single<AgentMembers> { launches }
                },
                ArtifactsModule.koinModule,
            )
        }
        val agent = AuthenticatedPrincipal("launch-1", PrincipalKind.AGENT, setOf("artifacts:write"))
        assertEquals(dave.member, application.koin.get<ArtifactOrgResolver>().resolve(agent, null))
        application.close()
    }

    @Test
    fun theBucketStoreIsBuiltOnTheCheckedClientAndClosedWithIt() {
        val application = koinApplication { modules(ArtifactsModule.koinModule) }
        assertIs<S3ObjectStore>(application.koin.get<ArtifactObjectStore>())
        application.close()
    }
}
