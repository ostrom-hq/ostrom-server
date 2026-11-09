// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.governance

import run.ostrom.server.governance.domain.ExpireConfirmationsUseCase
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** The module's name, migration, bindings and sweep. */
class GovernanceModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithItsMigration() {
        assertEquals("governance", GovernanceModule.name)
        assertEquals(listOf(1L, 2L), GovernanceModule.migrations.map { it.version })
        koinApplication { modules(GovernanceModule.koinModule) }.close()
    }

    @Test
    fun startingTheModuleRunsTheSweep() = runTest {
        val swept = CompletableDeferred<Unit>()
        val expire = object : ExpireConfirmationsUseCase {
            override suspend fun inOrganization(orgId: Uuid) = Unit

            override suspend fun everywhere(): Int {
                swept.complete(Unit)
                return 0
            }
        }
        val logger = object : Logger {
            override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) = Unit

            override fun with(fields: Map<String, Any?>): Logger = this
        }
        val koin = koinApplication {
            modules(
                module {
                    single<ExpireConfirmationsUseCase> { expire }
                    single<Logger> { logger }
                },
            )
        }.koin
        val job = Job()
        GovernanceModule.start(CoroutineScope(coroutineContext + job), koin)
        swept.await()
        job.cancel()
    }
}
