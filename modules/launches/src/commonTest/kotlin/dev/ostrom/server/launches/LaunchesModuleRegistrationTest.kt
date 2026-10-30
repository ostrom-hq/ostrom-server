// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches

import dev.ostrom.server.launches.domain.services.LaunchModelRules
import dev.ostrom.server.launches.fakes.FakeModelRules
import dev.ostrom.server.launches.fakes.ORG
import dev.ostrom.server.launches.infrastructure.NoLaunchModelRules
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class LaunchesModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("launches", LaunchesModule.name)
        assertEquals(1, LaunchesModule.migrations.size)
        koinApplication { modules(LaunchesModule.koinModule) }.close()
    }

    @Test
    fun withoutTheGatewayEveryModelIsAllowedAtLaunch() = runTest {
        val koin = koinApplication { }.koin
        LaunchesModule.initialize(koin)
        val rules = koin.get<LaunchModelRules>()
        assertIs<NoLaunchModelRules>(rules)
        assertNull(rules.refusal(ORG, "ada", "gpt-5"))
    }

    @Test
    fun theGatewaysModelRulesAreKept() = runTest {
        val gateway = FakeModelRules()
        val koin = koinApplication { modules(module { single<LaunchModelRules> { gateway } }) }.koin
        LaunchesModule.initialize(koin)
        assertSame(gateway, koin.get<LaunchModelRules>())
    }
}
