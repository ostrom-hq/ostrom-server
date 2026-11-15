// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations

import run.ostrom.server.automations.domain.AutomationDefinition
import run.ostrom.server.automations.domain.InvalidAutomationException
import run.ostrom.server.automations.domain.ManageAutomationsUseCaseImpl
import run.ostrom.server.automations.domain.RunAutomationsUseCaseImpl
import run.ostrom.server.automations.domain.WorkflowInputSpec
import run.ostrom.server.automations.fakes.InMemoryAutomations
import run.ostrom.server.automations.fakes.ORG_A
import run.ostrom.server.automations.fakes.PlainSecrets
import run.ostrom.server.automations.fakes.RecordingLauncher
import run.ostrom.server.automations.fakes.RecordingWorkflows
import run.ostrom.server.automations.fakes.TestClock
import run.ostrom.server.automations.fakes.aliceAuto
import run.ostrom.server.automations.fakes.carolAuto
import run.ostrom.server.automations.fakes.cronDefinition
import run.ostrom.server.automations.fakes.workflowDefinition
import run.ostrom.server.automations.presentation.AutomationActionRequest
import run.ostrom.server.automations.presentation.AutomationRequest
import run.ostrom.server.automations.presentation.toDefinition
import run.ostrom.server.kernel.sessions.CloudProfileDirectory
import run.ostrom.server.kernel.sessions.NamedCloudProfile
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** An action names the cloud profile it runs on (TeamCode's `action.profileId`). */
class ActionProfileTest {
    private val profile = Uuid.parse("00000000-0000-0000-0000-0000000f0001")
    private val repository = InMemoryAutomations()
    private val launcher = RecordingLauncher()
    private val workflows = RecordingWorkflows().apply {
        inputs["triage"] = listOf(
            WorkflowInputSpec("ticket", required = false, hasDefault = false),
            WorkflowInputSpec("note", required = false, hasDefault = false),
        )
    }
    private val clock = TestClock(Instant.parse("2026-11-14T10:00:00Z"))
    private val directory = CloudProfileDirectory { orgId ->
        if (orgId == ORG_A) listOf(NamedCloudProfile(profile, "backend")) else emptyList()
    }
    private val manage = ManageAutomationsUseCaseImpl(repository, PlainSecrets, clock, workflows, directory)
    private val runs = RunAutomationsUseCaseImpl(repository, launcher, PlainSecrets, null, clock, workflows = workflows)

    private fun session(profileId: String?) =
        cronDefinition().let { it.copy(action = it.action.copy(profileId = profileId)) }

    private fun workflow(profileId: String?) =
        workflowDefinition().let { it.copy(action = it.action.copy(profileId = profileId)) }

    private suspend fun refusal(definition: AutomationDefinition, manager: ManageAutomationsUseCaseImpl = manage) =
        assertFailsWith<InvalidAutomationException> { manager.create(aliceAuto, definition) }

    @Test
    fun aSessionActionKeepsItsProfileAndItsRunsLaunchOnIt() = runTest {
        val automation = manage.create(aliceAuto, session(profile.toString())).automation
        assertEquals(profile.toString(), automation.definition.action.profileId)
        runs.test(aliceAuto, automation.id, null, "k1")
        assertEquals(profile.toString(), launcher.launched.single().action.profileId)
    }

    @Test
    fun aWorkflowActionsProfileGoesToTheRunItStarts() = runTest {
        val automation = manage.create(aliceAuto, workflow(profile.toString())).automation
        runs.test(aliceAuto, automation.id, null, "k1")
        assertEquals(profile.toString(), workflows.started.single().profileId)
    }

    @Test
    fun anActionWithoutAProfileLeavesTheChoiceToTheSessionsCreation() = runTest {
        val automation = manage.create(aliceAuto, workflow(null)).automation
        runs.test(aliceAuto, automation.id, null, "k1")
        assertEquals(null, workflows.started.single().profileId)
    }

    @Test
    fun aProfileThatIsNotAnIdOrNoneOfTheOrganizationsIsRefusedNamingTheField() = runTest {
        val malformed = refusal(session("backend"))
        assertEquals("action.profileId: a cloud profile's id (a UUID).", malformed.message)
        assertEquals("invalid_request", malformed.type)
        val unknown = refusal(workflow(Uuid.random().toString()))
        assertEquals("action.profileId: no cloud profile of the organization has this id.", unknown.message)
        val saved = manage.create(aliceAuto, session(null)).automation
        assertFailsWith<InvalidAutomationException> {
            manage.update(aliceAuto, saved.id, 1, session(Uuid.random().toString()))
        }
        assertEquals(emptyList(), repository.automations.values.mapNotNull { it.definition.action.profileId })
    }

    @Test
    fun aProfileDeletedSinceTheAutomationNamedItNeverBlocksAnotherChange() = runTest {
        val saved = manage.create(aliceAuto, session(profile.toString())).automation
        val none = CloudProfileDirectory { emptyList() }
        val gone = ManageAutomationsUseCaseImpl(repository, PlainSecrets, clock, workflows, none)
        val renamed = session(profile.toString()).copy(name = "Renamed")
        assertEquals("Renamed", gone.update(aliceAuto, saved.id, 1, renamed).definition.name)
        assertFailsWith<InvalidAutomationException> {
            gone.update(aliceAuto, saved.id, 2, session(Uuid.random().toString()))
        }
    }

    @Test
    fun anotherOrganizationsProfileIsUnknown() = runTest {
        assertFailsWith<InvalidAutomationException> { manage.create(carolAuto, session(profile.toString())) }
    }

    @Test
    fun whereSessionsRunOnNoProfileAnyIdIsKeptUnchecked() = runTest {
        val bare = ManageAutomationsUseCaseImpl(repository, PlainSecrets, clock, workflows)
        val id = Uuid.random().toString()
        assertEquals(id, bare.create(aliceAuto, session(id)).automation.definition.action.profileId)
        assertEquals("action.profileId: a cloud profile's id (a UUID).", refusal(session("x"), bare).message)
    }

    @Test
    fun theRequestCarriesTheProfileIntoTheDefinition() {
        val request = AutomationRequest(
            name = "Nightly",
            trigger = cronDefinition().trigger,
            action = AutomationActionRequest(prompt = "Hi", profileId = profile.toString()),
        )
        assertEquals(profile.toString(), request.toDefinition().action.profileId)
    }
}
