// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets

import run.ostrom.server.budgets.domain.PoolSizeReport
import run.ostrom.server.budgets.domain.PoolSizingService
import run.ostrom.server.gateway.domain.PacingSettings
import run.ostrom.server.gateway.domain.PacingSettingsRepository
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.sharing.domain.SharingOrgs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class BudgetsFeaturesTest {
    private val orgs = SharingOrgs { emptyList() }
    private val settings = object : PacingSettingsRepository {
        override suspend fun get(orgId: Uuid) = PacingSettings()

        override suspend fun update(orgId: Uuid, observationOnly: Boolean?, autoPoolSize: Boolean?) =
            PacingSettings() to PacingSettings()
    }

    @Test
    fun itsFeaturesNeedNoConfiguration() = runTest {
        val koin = koinApplication {}.koin
        assertEquals(setOf("budgets.v1", "capacity.budgetoverrides.v1"), BudgetsModule.features(koin))
        // Nor a job without the shared accounts and the gateway's settings: nothing is launched.
        BudgetsModule.start(this, koin)
    }

    @Test
    fun thePoolSizeIsServedWithTheSharedAccountsAndTheGatewaysSettings() = runTest {
        val all = koinApplication { modules(module { single { orgs }; single<PacingSettingsRepository> { settings } }) }
        assertEquals(
            setOf("budgets.v1", "capacity.budgetoverrides.v1", "capacity.poolsize.v1"),
            BudgetsModule.features(all.koin),
        )
        val sharingOnly = koinApplication { modules(module { single { orgs } }) }
        assertEquals(setOf("budgets.v1", "capacity.budgetoverrides.v1"), BudgetsModule.features(sharingOnly.koin))
    }

    @Test
    fun theModuleRunsThePoolSizePassInTheServersScope() = runTest {
        val refreshed = CompletableDeferred<Unit>()
        val sizing = object : PoolSizingService {
            override suspend fun report(caller: Caller): PoolSizeReport = error("not read")

            override suspend fun refresh() {
                refreshed.complete(Unit)
            }
        }
        val koin = koinApplication {
            modules(
                module {
                    single { orgs }
                    single<PacingSettingsRepository> { settings }
                    single<PoolSizingService> { sizing }
                    single<Logger> { RecordingLogger() }
                },
            )
        }.koin
        val job = Job()
        BudgetsModule.start(CoroutineScope(coroutineContext + job), koin)
        refreshed.await()
        job.cancel()
    }
}
