// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.launches

import run.ostrom.server.kernel.auth.ModelAccess
import run.ostrom.server.launches.domain.services.LaunchModelRules
import run.ostrom.server.launches.domain.services.LauncherResolver
import run.ostrom.server.launches.fakes.ADA
import run.ostrom.server.launches.fakes.FakeLaunchers
import run.ostrom.server.launches.fakes.FakeModelRules
import run.ostrom.server.launches.infrastructure.ModelAccessLaunchRules
import run.ostrom.server.launches.infrastructure.NoLaunchModelRules
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.uuid.Uuid

class LaunchesModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("launches", LaunchesModule.name)
        assertEquals(6, LaunchesModule.migrations.size)
        koinApplication { modules(LaunchesModule.koinModule) }.close()
    }

    @Test
    fun withoutTheGatewayEveryModelIsAllowedAtLaunch() = runTest {
        val koin = koinApplication { }.koin
        LaunchesModule.initialize(koin)
        val rules = koin.get<LaunchModelRules>()
        assertIs<NoLaunchModelRules>(rules)
        assertNull(rules.refusal(ADA, "gpt-5"))
    }

    @Test
    fun withTheGatewayALaunchFollowsItsModelPolicy() = runTest {
        val access = object : ModelAccess {
            override suspend fun refusal(orgId: Uuid, memberId: Uuid, model: String): String? = "No $model."
        }
        val bindings = module {
            single<ModelAccess> { access }
            single<LauncherResolver> { FakeLaunchers(mapOf("ada" to ADA)) }
        }
        val koin = koinApplication { modules(bindings) }.koin
        LaunchesModule.initialize(koin)
        val rules = koin.get<LaunchModelRules>()
        assertIs<ModelAccessLaunchRules>(rules)
        assertEquals("No gpt-5.", rules.refusal(ADA, "gpt-5"))
    }

    @Test
    fun theGatewaysModelRulesAreKept() = runTest {
        val gateway = FakeModelRules()
        val koin = koinApplication { modules(module { single<LaunchModelRules> { gateway } }) }.koin
        LaunchesModule.initialize(koin)
        assertSame(gateway, koin.get<LaunchModelRules>())
    }
}
