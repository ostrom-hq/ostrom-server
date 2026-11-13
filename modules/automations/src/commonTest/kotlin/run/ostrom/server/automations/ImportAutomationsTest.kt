// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations

import run.ostrom.server.automations.domain.AutomationImport
import run.ostrom.server.automations.domain.AutomationLimitException
import run.ostrom.server.automations.domain.ImportAutomationsUseCaseImpl
import run.ostrom.server.automations.domain.ImportedAutomation
import run.ostrom.server.automations.domain.InvalidAutomationException
import run.ostrom.server.automations.domain.ManageAutomationsUseCaseImpl
import run.ostrom.server.automations.domain.StaleAutomationException
import run.ostrom.server.automations.domain.WorkflowInputSpec
import run.ostrom.server.automations.domain.importedAutomationId
import run.ostrom.server.automations.fakes.InMemoryAutomations
import run.ostrom.server.automations.fakes.ORG_A
import run.ostrom.server.automations.fakes.ORG_B
import run.ostrom.server.automations.fakes.RecordingWorkflows
import run.ostrom.server.automations.fakes.TestClock
import run.ostrom.server.automations.fakes.aliceAuto
import run.ostrom.server.automations.fakes.bobAuto
import run.ostrom.server.automations.fakes.cronDefinition
import run.ostrom.server.automations.fakes.workflowDefinition
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ImportAutomationsTest {
    private val repository = InMemoryAutomations()
    private val clock = TestClock(Instant.parse("2026-11-13T10:00:00Z"))
    private val workflows = RecordingWorkflows().apply {
        inputs["triage"] = listOf(
            WorkflowInputSpec("ticket", required = true, hasDefault = false),
            WorkflowInputSpec("note", required = true, hasDefault = true),
        )
    }
    private val imports = ImportAutomationsUseCaseImpl(repository, clock, workflows)

    private fun nightly(enabled: Boolean? = null, prompt: String = "Summarize {{run.date}}") =
        ImportedAutomation("teamcode/a1", aliceAuto.memberId, cronDefinition(prompt = prompt), enabled)

    @Test
    fun anImportedAutomationHasAnIdOfItsOrganizationAndSourceAndLandsPaused() = runTest {
        val id = importedAutomationId(ORG_A, "teamcode/a1")
        assertEquals(id, importedAutomationId(ORG_A, "teamcode/a1"))
        assertNotEquals(id, importedAutomationId(ORG_B, "teamcode/a1"))
        assertNotEquals(id, importedAutomationId(ORG_A, "teamcode/a2"))
        assertEquals(5, id.toString()[14].digitToInt(), "A version 5 layout")

        assertEquals(AutomationImport.CREATED, imports.import(ORG_A, nightly(), dryRun = true))
        assertTrue(repository.automations.isEmpty(), "A dry run writes nothing")
        assertEquals(AutomationImport.CREATED, imports.import(ORG_A, nightly(), dryRun = false))
        val created = repository.automations.getValue(id)
        assertFalse(created.enabled)
        assertNull(created.nextRunAt)
        assertEquals(aliceAuto.memberId, created.owner)
        assertEquals(1, created.version)
        assertEquals(AutomationImport.UNCHANGED, imports.import(ORG_A, nightly(), dryRun = false))
    }

    @Test
    fun aChangedDefinitionIsANewVersionAndTheCutoverArmsWithoutOne() = runTest {
        val id = importedAutomationId(ORG_A, "teamcode/a1")
        imports.import(ORG_A, nightly(), dryRun = false)
        val changed = nightly(prompt = "Report {{run.date}}")
        assertEquals(AutomationImport.UPDATED, imports.import(ORG_A, changed, dryRun = true))
        assertEquals(1, repository.automations.getValue(id).version)
        assertEquals(AutomationImport.UPDATED, imports.import(ORG_A, changed, dryRun = false))
        assertEquals(2, repository.automations.getValue(id).version)

        val cutover = nightly(enabled = true, prompt = "Report {{run.date}}")
        assertEquals(AutomationImport.UPDATED, imports.import(ORG_A, cutover, dryRun = false))
        val armed = repository.automations.getValue(id)
        assertTrue(armed.enabled)
        assertNotNull(armed.nextRunAt)
        assertEquals(2, armed.version, "Arming it changes no definition")
        assertEquals(AutomationImport.UNCHANGED, imports.import(ORG_A, cutover, dryRun = false))
        assertEquals(AutomationImport.UNCHANGED, imports.import(ORG_A, changed, dryRun = false), "Left armed")
        assertEquals(AutomationImport.UPDATED, imports.import(ORG_A, nightly(enabled = false), dryRun = false))
        assertFalse(repository.automations.getValue(id).enabled)
        assertNull(repository.automations.getValue(id).nextRunAt)
    }

    @Test
    fun aCutoverCreatesItArmedAndALostUpdateIsStale() = runTest {
        val armed = ImportedAutomation("teamcode/a9", aliceAuto.memberId, cronDefinition(), enabled = true)
        imports.import(ORG_A, armed, dryRun = false)
        assertTrue(repository.automations.getValue(importedAutomationId(ORG_A, "teamcode/a9")).enabled)
        repository.loseNextUpdate = true
        assertFailsWith<StaleAutomationException> {
            imports.import(ORG_A, armed.copy(enabled = false), dryRun = false)
        }
    }

    @Test
    fun anotherOwnerAnInvalidDefinitionAWorkflowThatDoesNotFitAndTheCapAreRefused() = runTest {
        imports.import(ORG_A, nightly(), dryRun = false)
        val moved = assertFailsWith<InvalidAutomationException> {
            imports.import(ORG_A, nightly().copy(owner = bobAuto.memberId), dryRun = true)
        }
        assertTrue("another member" in moved.message.orEmpty())
        assertFailsWith<InvalidAutomationException> {
            imports.import(ORG_A, nightly(prompt = "{{ nope }}"), dryRun = true)
        }

        val flow = ImportedAutomation("teamcode/w1", aliceAuto.memberId, workflowDefinition(), null)
        assertEquals(AutomationImport.CREATED, imports.import(ORG_A, flow.copy(sourceKey = "x"), dryRun = true))
        val definition = workflowDefinition()
        val gone = flow.copy(definition = definition.copy(action = definition.action.copy(workflowId = "gone")))
        assertEquals(AutomationImport.CREATED, imports.import(ORG_A, gone, dryRun = true), "It may come first")
        assertFailsWith<InvalidAutomationException> { imports.import(ORG_A, gone, dryRun = false) }
        assertEquals(AutomationImport.CREATED, imports.import(ORG_A, flow, dryRun = false))

        val owned = ManageAutomationsUseCaseImpl.MAX_PER_MEMBER - repository.automations.size
        repeat(owned) { imports.import(ORG_A, nightly().copy(sourceKey = "teamcode/n$it"), dryRun = false) }
        assertFailsWith<AutomationLimitException> {
            imports.import(ORG_A, nightly().copy(sourceKey = "teamcode/over"), dryRun = false)
        }
    }
}
