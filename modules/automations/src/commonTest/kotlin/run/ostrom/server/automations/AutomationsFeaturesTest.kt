// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import run.ostrom.server.automations.domain.WaitSecretSessions
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import kotlin.test.Test
import kotlin.test.assertEquals

class AutomationsFeaturesTest {
    @Test
    fun itsFeaturesNeedNoConfiguration() = runTest {
        val koin = koinApplication {}.koin
        assertEquals(
            setOf("automations.v1", "waits.v1", "waits.wake.v1", "waits.timed.v1"),
            AutomationsModule.features(koin),
        )
    }

    @Test
    fun secretFieldsNeedAKeyAndASessionsDirectory() = runTest {
        val key = module { single<SecretCipher> { AesGcmSecretCipher(ByteArray(32)) } }
        val sessions = module { single<WaitSecretSessions> { WaitSecretSessions { _, _ -> "claude" } } }
        suspend fun announced(vararg modules: org.koin.core.module.Module) =
            "waits.secrets.v1" in AutomationsModule.features(koinApplication { modules(*modules) }.koin)
        assertEquals(false, announced(key))
        assertEquals(false, announced(sessions))
        assertEquals(true, announced(key, sessions))
    }
}
