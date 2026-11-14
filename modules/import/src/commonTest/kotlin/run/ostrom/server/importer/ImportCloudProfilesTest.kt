// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer

import run.ostrom.server.automations.domain.AutomationImport
import run.ostrom.server.automations.domain.ImportAutomationsUseCase
import run.ostrom.server.automations.domain.ImportedAutomation
import run.ostrom.server.compiler.domain.usecases.ImportWorkflowsUseCase
import run.ostrom.server.compiler.domain.usecases.WorkflowImport
import run.ostrom.server.importer.domain.ImportItem
import run.ostrom.server.importer.domain.ProcessRunMode
import run.ostrom.server.importer.domain.TeamCodeAutomation
import run.ostrom.server.importer.domain.TeamCodeAutomationMapper
import run.ostrom.server.importer.domain.TeamCodeMapping
import run.ostrom.server.importer.domain.TeamCodeMember
import run.ostrom.server.importer.domain.TeamCodeProcessImporter
import run.ostrom.server.importer.domain.TeamCodeProcesses
import run.ostrom.server.importer.domain.TeamCodeProfile
import run.ostrom.server.importer.domain.TeamCodeWorkflow
import run.ostrom.server.importer.domain.TeamCodeWorkflowMapper
import run.ostrom.server.importer.domain.matchCloudProfiles
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.sessions.CloudProfileDirectory
import run.ostrom.server.kernel.sessions.NamedCloudProfile
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** TeamCode's profiles become the Ostrom cloud profiles they match, named by the automations and agent nodes. */
class ImportCloudProfilesTest {
    private val backend = Uuid.parse("00000000-0000-0000-0000-0000000f0001")
    private val same = Uuid.parse("00000000-0000-0000-0000-0000000f0002")
    private val ostrom = listOf(
        NamedCloudProfile(backend, " Backend "),
        NamedCloudProfile(same, "Shared"),
        NamedCloudProfile(Uuid.random(), "shared"),
        NamedCloudProfile(Uuid.random(), ""),
    )
    private val teamCode = listOf(
        TeamCodeProfile("p-api", "backend", listOf("acme/api")),
        TeamCodeProfile(same.toString(), "Renamed"),
        TeamCodeProfile("p-shared", "Shared"),
        TeamCodeProfile("p-anon"),
        TeamCodeProfile("p-blank", " "),
        TeamCodeProfile("p-other", "Other"),
    )
    private val matched = mapOf("p-api" to backend.toString(), same.toString() to same.toString())

    @Test
    fun aProfileMatchesTheOneWithItsIdElseTheOnlyOneWithItsName() {
        assertEquals(matched, matchCloudProfiles(teamCode, ostrom))
        assertEquals(emptyMap(), matchCloudProfiles(teamCode, emptyList()))
    }

    private val lena = Uuid.parse("0000000a-0000-0000-0000-0000000000a1")
    private val nightly = TeamCodeAutomation(
        "a1", "m1", "Nightly", "cron", "0 21 * * *", "Europe/Paris", actionKind = "cloud", profileId = "p-api",
        prompt = "Go",
    )

    @Test
    fun anAutomationNamesTheMatchedProfileAndOneWithoutAMatchIsNoted() {
        val profiles = teamCode.associateBy { it.id }
        val mapped = TeamCodeAutomationMapper.map(nightly, lena, profiles, false, matched)
        assertEquals(backend.toString(), mapped.automation?.definition?.action?.profileId)
        val workflow = nightly.copy(actionKind = "workflow", workflowId = "w1", prompt = "")
        val started = TeamCodeAutomationMapper.map(workflow, lena, profiles, false, matched)
        assertEquals(backend.toString(), started.automation?.definition?.action?.profileId)
        val other = nightly.copy(profileId = "p-other")
        val unmatched = TeamCodeAutomationMapper.map(other, lena, profiles, false, matched)
        assertNull(unmatched.automation?.definition?.action?.profileId)
        val noted = unmatched.notes.any { it.startsWith("Its action: no Ostrom cloud profile has") }
        assertTrue(noted, "${unmatched.notes}")
        // Where sessions run on no profile, nothing is named and nothing is noted about it.
        val bare = TeamCodeAutomationMapper.map(other, lena, profiles, false)
        assertNull(bare.automation?.definition?.action?.profileId)
        assertTrue(bare.notes.none { "cloud profile" in it }, "${bare.notes}")
        val none = TeamCodeAutomationMapper.map(nightly.copy(profileId = null), lena, profiles, false, matched)
        assertNull(none.automation?.definition?.action?.profileId)
    }

    private fun definition(profileId: String): JsonObject = Json.parseToJsonElement(
        """
        {"start": "a", "nodes": [
          {"id": "a", "kind": "agent", "title": "A", "instruction": "Go", "profileId": "$profileId",
           "transitions": [{"on": "delivered"}]}
        ]}
        """,
    ).jsonObject

    private fun profileOfNode(raw: JsonObject?): String? =
        raw?.getValue("nodes")?.jsonArray?.single()?.jsonObject?.get("run")?.jsonObject?.get("profileId")
            ?.jsonPrimitive?.content

    @Test
    fun anAgentNodeNamesTheMatchedProfileAndOneWithoutAMatchIsNoted() {
        val mapping = TeamCodeMapping(emptyMap(), teamCode.associateBy { it.id }, matched)
        val known = TeamCodeWorkflowMapper.map(TeamCodeWorkflow("w1", "Fix", 1, definition("p-api")), mapping)
        assertEquals(backend.toString(), profileOfNode(known.raw))
        val unknown = TeamCodeWorkflowMapper.map(TeamCodeWorkflow("w2", "Fix", 1, definition("p-other")), mapping)
        assertNull(profileOfNode(unknown.raw))
        assertTrue(unknown.notes.any { it.startsWith("Node a: no Ostrom cloud profile has") }, "${unknown.notes}")
    }

    @Test
    fun theImporterMatchesTheOrganizationsProfilesOnce() = runTest {
        val workflows = mutableListOf<JsonObject>()
        val automations = mutableListOf<ImportedAutomation>()
        val asked = mutableListOf<Uuid>()
        val importer = TeamCodeProcessImporter(
            object : ImportWorkflowsUseCase {
                override suspend fun import(orgId: Uuid, raw: JsonObject, by: String, dryRun: Boolean) =
                    WorkflowImport.CREATED.also { workflows += raw }
            },
            object : ImportAutomationsUseCase {
                override suspend fun import(orgId: Uuid, automation: ImportedAutomation, dryRun: Boolean) =
                    AutomationImport.CREATED.also { automations += automation }
            },
            null,
            null,
            CloudProfileDirectory { orgId -> ostrom.also { asked += orgId } },
        )
        val processes = TeamCodeProcesses(
            workflows = listOf(TeamCodeWorkflow("w1", "Fix", 1, definition("p-api"))),
            automations = listOf(nightly),
            profiles = teamCode,
        )
        val caller = Importing.adminA
        val principal = AuthenticatedPrincipal("user-nina", PrincipalKind.USER, setOf("import:write"))
        val items = mutableListOf<ImportItem>()
        importer.import(
            caller,
            processes,
            listOf(TeamCodeMember("m1", "lena")),
            mapOf("m1" to lena),
            ProcessRunMode(dryRun = false, cutover = false, principal = principal),
            items,
        )
        assertEquals(listOf(caller.orgId), asked)
        assertEquals(backend.toString(), profileOfNode(workflows.single()))
        assertEquals(backend.toString(), automations.single().definition.action.profileId)
    }
}
