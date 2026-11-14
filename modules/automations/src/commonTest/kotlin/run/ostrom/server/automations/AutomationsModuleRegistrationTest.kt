// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations

import run.ostrom.server.automations.domain.AutomationWorkflows
import run.ostrom.server.automations.domain.AutomationsRepository
import run.ostrom.server.automations.domain.ImportAutomationsUseCase
import run.ostrom.server.automations.domain.ImportedAutomation
import run.ostrom.server.automations.domain.InvalidAutomationException
import run.ostrom.server.automations.domain.ManageAutomationsUseCase
import run.ostrom.server.automations.domain.RunAutomationsUseCase
import run.ostrom.server.automations.domain.WebhookSecrets
import run.ostrom.server.automations.domain.WorkflowInputSpec
import run.ostrom.server.automations.fakes.InMemoryAutomations
import run.ostrom.server.automations.fakes.PlainSecrets
import run.ostrom.server.automations.fakes.RecordingWorkflows
import run.ostrom.server.automations.fakes.TestClock
import run.ostrom.server.automations.fakes.aliceAuto
import run.ostrom.server.automations.fakes.workflowDefinition
import run.ostrom.server.kernel.config.MapConfigSource
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock

class AutomationsModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("automations", AutomationsModule.name)
        koinApplication { modules(AutomationsModule.koinModule) }.close()
    }

    @Test
    fun theWorkflowsOfTheModuleThatRunsThemReachTheUseCases() = runTest {
        val workflows = RecordingWorkflows().apply {
            inputs["triage"] = listOf(WorkflowInputSpec("ticket", required = true, hasDefault = false))
        }
        val koin = koinApplication {
            allowOverride(true)
            modules(
                AutomationsModule.koinModule,
                module {
                    single<AutomationsRepository> { InMemoryAutomations() }
                    single<WebhookSecrets> { PlainSecrets }
                    single<Clock> { TestClock() }
                    single<AutomationWorkflows> { workflows }
                },
            )
        }.koin
        val created = koin.get<ManageAutomationsUseCase>()
            .create(aliceAuto, workflowDefinition(inputs = mapOf("ticket" to "T")))
            .automation
        koin.get<RunAutomationsUseCase>().test(aliceAuto, created.id, null, "t1")
        assertEquals("triage", workflows.started.single().workflowId)
        val definition = workflowDefinition(inputs = emptyMap())
        val imported = ImportedAutomation("teamcode/a1", aliceAuto.memberId, definition, null)
        // The import checks a workflow action against the bound workflows too: `ticket` is required.
        assertFailsWith<InvalidAutomationException> {
            koin.get<ImportAutomationsUseCase>().import(aliceAuto.orgId, imported, dryRun = false)
        }
    }

    @Test
    fun withoutAModuleThatRunsWorkflowsTheImportRefusesAWorkflowAction() = runTest {
        val koin = koinApplication {
            allowOverride(true)
            modules(
                AutomationsModule.koinModule,
                module {
                    single<AutomationsRepository> { InMemoryAutomations() }
                    single<WebhookSecrets> { PlainSecrets }
                    single<Clock> { TestClock() }
                },
            )
        }.koin
        val imported = ImportedAutomation("teamcode/a1", aliceAuto.memberId, workflowDefinition(), null)
        assertFailsWith<InvalidAutomationException> {
            koin.get<ImportAutomationsUseCase>().import(aliceAuto.orgId, imported, dryRun = false)
        }
    }

    @Test
    fun webhookUrlsUseThePublicUrlWithoutItsTrailingSlash() {
        val configured = MapConfigSource(mapOf("OSTROM_PUBLIC_URL" to "https://ostrom.example/"))
        assertEquals("https://ostrom.example", publicUrlOf(configured))
        assertEquals("", publicUrlOf(MapConfigSource(emptyMap())))
    }
}
