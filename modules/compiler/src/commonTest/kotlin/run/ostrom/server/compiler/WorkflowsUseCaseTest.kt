// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler

import run.ostrom.core.api.models.NodePosition
import run.ostrom.core.api.models.NodeRun
import run.ostrom.core.api.models.Workflow
import run.ostrom.core.api.models.WorkflowNodeKind.AGENT
import run.ostrom.core.api.models.WorkflowNodeKind.CHECK
import run.ostrom.core.api.models.WorkflowNodeKind.GATE
import run.ostrom.core.api.models.WorkflowNodeKind.NOTIFY
import run.ostrom.core.api.models.WorkflowNodeKind.STEP
import run.ostrom.core.api.models.WorkflowNodeKind.WORKFLOW
import run.ostrom.core.api.models.WorkflowStatus
import run.ostrom.server.compiler.domain.models.PackagePin
import run.ostrom.server.compiler.domain.services.PackageCompiler
import run.ostrom.server.compiler.domain.services.WorkflowRepos
import run.ostrom.server.compiler.domain.usecases.CompileSourcesUseCaseImpl
import run.ostrom.server.compiler.domain.usecases.PublishPackageUseCaseImpl
import run.ostrom.server.compiler.domain.usecases.RevokePackageUseCaseImpl
import run.ostrom.server.compiler.domain.usecases.WorkflowImport
import run.ostrom.server.compiler.domain.usecases.WorkflowsUseCaseImpl
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.kernel.http.withNulls
import run.ostrom.server.kernel.workflows.AutomationUsage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class WorkflowsUseCaseTest {
    private val org = Uuid.parse("00000000-0000-0000-0000-00000000000a")
    private val repository = MemoryCompilerRepository()
    private val signer = RecordingDecisionSigner()
    private val clock = SettableClock()
    private val removed = mutableListOf<Pair<Uuid, String>>()
    private val workflows = WorkflowsUseCaseImpl(repository, signer, clock, removals = { o, i -> removed += o to i })

    private fun raw(workflow: Workflow): JsonObject =
        PackageCompiler.json.encodeToJsonElement(Workflow.serializer(), workflow).withNulls("edges.*.to").jsonObject

    private fun release(status: WorkflowStatus = WorkflowStatus.PROPOSED, x: Double = 0.0) = workflow(
        "release",
        listOf(
            node("s", STEP, label = "Write the notes"),
            node("c", CHECK, label = "Tests", run = NodeRun(command = "make test")),
            node("g", GATE, label = "Deploy", approvers = listOf("lead")),
            node("o", GATE, label = "Rollback"),
            node("n", NOTIFY, label = "Tell", run = NodeRun(channel = "slack:#rel")),
        ).map { if (it.id == "s") it.copy(position = NodePosition(x, 0.0)) else it },
        name = "Release acme-api",
        status = status,
    )

    private suspend fun expectFailure(kind: ErrorKind, block: suspend () -> Unit) {
        val error = assertFailsWith<OstromException> { block() }
        assertEquals(kind, error.kind)
    }

    @Test
    fun aCreationIsAnEditDecisionWithTheGatesAndWhatRuns() = runTest {
        val saved = workflows.save(org, raw(release()), null, "dev-a", create = true)

        assertEquals(1, saved.version)
        val (action, before, after) = signer.signed.single()
        assertEquals("edit", action)
        assertNull(before)
        assertEquals(JsonPrimitive("proposed"), after["status"])
        assertEquals(
            JsonArray(listOf(JsonPrimitive("Deploy: lead"), JsonPrimitive("Rollback: nobody"))),
            after["gates"],
        )
        assertEquals(
            JsonArray(listOf(JsonPrimitive("Tests: runs `make test`"), JsonPrimitive("Tell: tells slack:#rel"))),
            after["runs"],
        )
        val decision = repository.lastDecisions(org).single()
        assertEquals("rcpt_wf_1", decision.receiptId)
        assertEquals("workflow", decision.subjectKind)
        assertEquals("release", decision.subjectId)
        assertEquals("2026-10-30T08:45:00.000Z", decision.at)
    }

    @Test
    fun acceptingAProposedWorkflowIsAnAcceptDecisionAndMovingANodeIsNone() = runTest {
        workflows.save(org, raw(release()), null, "dev-a", create = true)
        workflows.save(org, raw(release(x = 40.0)), 1, "dev-a", create = false)
        assertEquals(1, signer.signed.size, "a moved node is not a decision")

        val accepted = workflows.save(org, raw(release(WorkflowStatus.ACCEPTED)), 2, "lead", create = false)

        assertEquals(3, accepted.version)
        assertEquals("accept", signer.signed.last().first)
        assertEquals(JsonPrimitive("proposed"), signer.signed.last().second?.get("status"))
    }

    @Test
    fun aStaleVersionADuplicateAndAnInvalidDocumentAreRefused() = runTest {
        workflows.save(org, raw(release()), null, "dev-a", create = true)
        expectFailure(ErrorKind.CONFLICT) { workflows.save(org, raw(release()), null, "dev-a", create = true) }
        expectFailure(ErrorKind.CONFLICT) { workflows.save(org, raw(release(x = 9.0)), 7, "dev-a", create = false) }
        expectFailure(ErrorKind.INVALID_REQUEST) {
            workflows.save(org, JsonObject(mapOf("id" to JsonPrimitive("x"))), null, "dev-a", create = true)
        }
        expectFailure(ErrorKind.NOT_FOUND) { workflows.get(org, "missing") }
        expectFailure(ErrorKind.NOT_FOUND) { workflows.delete(org, "missing") }
    }

    @Test
    fun anUpdateOfAnUnknownWorkflowIsNotFoundAndCreatesNothing() = runTest {
        workflows.save(org, raw(release()), null, "dev-a", create = true)
        val elsewhere = Uuid.parse("0000000e-0000-0000-0000-000000000000")
        expectFailure(ErrorKind.NOT_FOUND) { workflows.save(elsewhere, raw(release()), null, "dev-e", create = false) }
        expectFailure(ErrorKind.NOT_FOUND) { workflows.save(elsewhere, raw(release()), 1, "dev-e", create = false) }
        expectFailure(ErrorKind.NOT_FOUND) { workflows.get(elsewhere, "release") }
        assertEquals(1, workflows.get(org, "release").version, "The other organization's workflow is untouched")

        // An update without a version replaces the version just read.
        assertEquals(2, workflows.save(org, raw(release()), null, "dev-a", create = false).version)

        // In the caller's own organization too: an id it deleted is not saved back.
        workflows.delete(org, "release")
        expectFailure(ErrorKind.NOT_FOUND) { workflows.save(org, raw(release()), null, "dev-a", create = false) }
        expectFailure(ErrorKind.NOT_FOUND) { workflows.get(org, "release") }
    }

    @Test
    fun theListFiltersByRepositoryAndStatus() = runTest {
        workflows.save(org, raw(release()), null, "dev-a", create = true)
        val other = workflow("web", listOf(node("s", STEP, label = "Ship the site")), name = "Website")
        workflows.save(org, raw(other), null, "dev-a", create = true)

        assertEquals(setOf("release", "web"), workflows.list(org, null, null).map { it.doc.workflow.id }.toSet())
        assertEquals(listOf("release"), workflows.list(org, "acme-api", null).map { it.doc.workflow.id })
        assertEquals(listOf("web"), workflows.list(org, null, WorkflowStatus.ACCEPTED).map { it.doc.workflow.id })
        assertEquals("release", workflows.get(org, "release").doc.workflow.id)
    }

    @Test
    fun aWorkflowsRepositoriesAreCheckedSignedAndLimitWhereItApplies() = runTest {
        fun limitedTo(vararg repos: String) =
            JsonObject(raw(release()) + (WorkflowRepos.FIELD to JsonArray(repos.map { JsonPrimitive(it) })))
        expectFailure(ErrorKind.INVALID_REQUEST) {
            workflows.save(org, limitedTo("acme"), null, "dev-a", create = true)
        }

        val saved = workflows.save(org, limitedTo("acme/docs"), null, "dev-a", create = true)
        assertEquals(listOf("acme/docs"), saved.doc.repos)
        assertEquals(JsonArray(listOf(JsonPrimitive("acme/docs"))), signer.signed.single().third["repos"])
        // R6: its name says acme-api, but it is limited to acme/docs.
        assertEquals(emptyList<String>(), workflows.list(org, "acme-api", null).map { it.doc.workflow.id })
        assertEquals(listOf("release"), workflows.list(org, "acme/docs", null).map { it.doc.workflow.id })

        // Changing only the repositories is a decision; saving the same is none; removing them is one.
        workflows.save(org, limitedTo("acme/*"), null, "dev-a", create = false)
        assertEquals(JsonArray(listOf(JsonPrimitive("acme/*"))), signer.signed.last().third["repos"])
        workflows.save(org, limitedTo("acme/*"), null, "dev-a", create = false)
        assertEquals(2, signer.signed.size)
        workflows.save(org, raw(release()), null, "dev-a", create = false)
        assertEquals(3, signer.signed.size)
        assertNull(signer.signed.last().third["repos"])
        assertEquals(listOf("release"), workflows.list(org, "acme-api", null).map { it.doc.workflow.id })
    }

    @Test
    fun aWorkflowCompiledInAPinnedPackageCannotBeDeleted() = runTest {
        val sources = FixedSources()
        val compiler = CompileSourcesUseCaseImpl(repository, sources, CountingHarnessSigner(), clock)
        val signer = SeedSigner(ByteArray(32))
        val publish = PublishPackageUseCaseImpl(compiler, repository, sources, signer, clock)
        workflows.save(org, raw(release(WorkflowStatus.ACCEPTED)), null, "lead", create = true)
        workflows.save(org, raw(workflow("web", listOf(node("s", STEP)), name = "Website")), null, "lead", true)
        val served = publish(org, "acme-api", "https://acme.example", "lead")

        expectFailure(ErrorKind.CONFLICT) { workflows.delete(org, "release") }
        assertEquals(emptyList(), removed, "A workflow kept keeps its budgets")
        workflows.delete(org, "web")
        assertNull(repository.workflow(org, "web"))
        assertEquals(listOf(org to "web"), removed, "Its budgets go with it")

        RevokePackageUseCaseImpl(repository, clock).revoke(org, served.stored.envelope.digest, "lead", "gone")
        workflows.delete(org, "release")
        assertTrue(repository.workflows(org).isEmpty())
    }

    @Test
    fun withoutAnyoneToTellAWorkflowIsDeletedAlone() = runTest {
        val alone = WorkflowsUseCaseImpl(repository, signer, clock)
        alone.save(org, raw(workflow("web", listOf(node("s", STEP)), name = "Website")), null, "lead", true)
        alone.delete(org, "web")
        assertNull(repository.workflow(org, "web"))
        assertEquals(emptyList(), removed)
    }

    @Test
    fun aPackageThatIsNotPinnedDoesNotHoldItsWorkflows() = runTest {
        val sources = FixedSources()
        val compiler = CompileSourcesUseCaseImpl(repository, sources, CountingHarnessSigner(), clock)
        val publish = PublishPackageUseCaseImpl(compiler, repository, sources, SeedSigner(ByteArray(32)), clock)
        workflows.save(org, raw(release(WorkflowStatus.ACCEPTED)), null, "lead", create = true)
        publish(org, "acme-api", "https://acme.example", "lead")

        workflows.save(org, raw(workflow("web", listOf(node("s", STEP)), name = "Website")), null, "lead", true)

        repository.setPin(org, "acme-api", PackagePin("another", "lead", NOW))
        workflows.delete(org, "web")
        repository.setPin(org, "acme-api", null)
        workflows.delete(org, "release")

        assertTrue(repository.workflows(org).isEmpty())
    }

    private fun agentFlow(id: String, name: String) = workflow(
        id,
        listOf(node("a", AGENT, run = NodeRun(instructions = "Do it"))),
        edges = listOf("a" to null),
        name = name,
    )

    private fun calls(id: String, name: String, target: String) = workflow(
        id,
        listOf(node("c", WORKFLOW).copy(workflowId = target)),
        name = name,
    )

    @Test
    fun aWorkflowTheServerRunsIsCheckedWhenSavedAndRefusedWithItsKey() = runTest {
        val broken = agentFlow("fix", "Fix").copy(start = "nowhere")
        val error = assertFailsWith<OstromException> { workflows.save(org, raw(broken), null, "lead", create = true) }
        assertEquals(ErrorKind.INVALID_REQUEST, error.kind)
        assertEquals("invalid_workflow", error.type)
        assertTrue("The start node nowhere does not exist." in error.message, error.message)
        assertTrue(repository.workflows(org).isEmpty())
        // The checks of a workflow of the harness only are today's.
        val harness = workflow("h", listOf(node("c", CHECK, run = NodeRun(command = "make"))), name = " ")
        assertEquals(1, workflows.save(org, raw(harness), null, "lead", create = true).version)
    }

    @Test
    fun everySaveAndDeleteTakesTheOrganizationsWorkflowLock() = runTest {
        workflows.save(org, raw(agentFlow("fix", "Fix")), null, "lead", create = true)
        workflows.save(org, raw(agentFlow("fix", "Fix")), 1, "lead", create = false)
        assertEquals(2, repository.locks)
        workflows.delete(org, "fix")
        assertEquals(3, repository.locks)
    }

    @Test
    fun aNameIsUniquePerOrganizationAmongTheWorkflowsTheServerRuns() = runTest {
        workflows.save(org, raw(agentFlow("fix", "Fix the build")), null, "lead", create = true)
        val taken = assertFailsWith<OstromException> {
            workflows.save(org, raw(agentFlow("other", " fix THE build ")), null, "lead", create = true)
        }
        assertEquals(ErrorKind.CONFLICT, taken.kind)
        assertEquals("workflow_name_taken", taken.type)
        // Its own name is its own, and another organization's names are not this one's.
        workflows.save(org, raw(agentFlow("fix", "Fix the build")), null, "lead", create = false)
        val elsewhere = Uuid.parse("0000000e-0000-0000-0000-000000000000")
        workflows.save(elsewhere, raw(agentFlow("other", "Fix the build")), null, "lead", create = true)
        assertEquals(1, repository.workflowCount(org))
    }

    @Test
    fun theCallsOfAWorkflowAreCheckedAgainstTheOrganizationsOthers() = runTest {
        workflows.save(org, raw(agentFlow("leaf", "Leaf")), null, "lead", create = true)
        workflows.save(org, raw(calls("mid", "Mid", "leaf")), null, "lead", create = true)
        val loop = assertFailsWith<OstromException> {
            workflows.save(org, raw(calls("leaf", "Leaf", "mid")), null, "lead", create = false)
        }
        assertEquals("invalid_workflow", loop.type)
        assertTrue("Workflow calls loop back to this one (leaf -> mid -> leaf)." in loop.message, loop.message)
        val missing = assertFailsWith<OstromException> {
            workflows.save(org, raw(calls("top", "Top", "gone")), null, "lead", create = true)
        }
        assertTrue("the workflow gone does not exist" in missing.message, missing.message)
    }

    @Test
    fun aWorkflowAnotherOneCallsOrAnAutomationStartsIsInUse() = runTest {
        val automation = AutomationUsage { _, id -> if (id == "leaf") "Nightly build" else null }
        val use = WorkflowsUseCaseImpl(repository, signer, clock, automation)
        use.save(org, raw(agentFlow("leaf", "Leaf")), null, "lead", create = true)
        use.save(org, raw(agentFlow("solo", "Solo")), null, "lead", create = true)
        use.save(org, raw(calls("mid", "Mid", "solo")), null, "lead", create = true)

        val called = assertFailsWith<OstromException> { use.delete(org, "solo") }
        assertEquals(ErrorKind.CONFLICT, called.kind)
        assertEquals("workflow_in_use", called.type)
        assertTrue("the workflow Mid calls it" in called.message, called.message)
        val started = assertFailsWith<OstromException> { use.delete(org, "leaf") }
        assertEquals("workflow_in_use", started.type)
        assertTrue("the automation Nightly build starts it" in started.message, started.message)

        use.delete(org, "mid")
        use.delete(org, "solo")
        assertEquals(listOf("leaf"), repository.workflows(org).map { it.doc.workflow.id })
    }

    @Test
    fun aProposalIsRejectedAndNothingElseIs() = runTest {
        workflows.save(org, raw(release()), null, "bot", create = true)
        val rejected = workflows.save(org, raw(release(WorkflowStatus.REJECTED)), 1, "lead", create = false)
        assertEquals(2, rejected.version)
        // A rejected proposal stays rejected when saved again, and an edit can still accept it.
        workflows.save(org, raw(release(WorkflowStatus.REJECTED, x = 9.0)), 2, "lead", create = false)
        workflows.save(org, raw(release(WorkflowStatus.ACCEPTED)), 3, "lead", create = false)

        val accepted = assertFailsWith<OstromException> {
            workflows.save(org, raw(release(WorkflowStatus.REJECTED)), 4, "lead", create = false)
        }
        assertEquals("invalid_workflow", accepted.type)
        assertTrue("only a proposed workflow is rejected" in accepted.message, accepted.message)
        val born = workflow("new", listOf(node("s", STEP)), status = WorkflowStatus.REJECTED)
        val created = assertFailsWith<OstromException> { workflows.save(org, raw(born), null, "lead", true) }
        assertEquals("invalid_workflow", created.type)
    }

    @Test
    fun aRejectedWorkflowCompilesNothing() = runTest {
        val compiler = CompileSourcesUseCaseImpl(repository, FixedSources(), CountingHarnessSigner(), clock)
        workflows.save(org, raw(release(WorkflowStatus.ACCEPTED)), null, "lead", create = true)
        val idea = release(WorkflowStatus.PROPOSED).copy(id = "idea", name = "Idea")
        workflows.save(org, raw(idea), null, "bot", create = true)
        val turnedDown = release(WorkflowStatus.PROPOSED).copy(id = "no", name = "No")
        workflows.save(org, raw(turnedDown), null, "bot", create = true)
        workflows.save(org, raw(turnedDown.copy(status = WorkflowStatus.REJECTED)), null, "lead", create = false)

        assertEquals(setOf("release", "idea", "no"), repository.workflows(org).map { it.doc.workflow.id }.toSet())
        val inputs = compiler.inputs(org, "acme-api")
        assertEquals(setOf("release", "idea"), inputs.workflows.map { it.workflow.id }.toSet())
    }

    @Test
    fun anImportCreatesAnImportDecisionThenSavesAChangeAndLeavesTheSameDocumentAlone() = runTest {
        val first = raw(release(WorkflowStatus.ACCEPTED))
        assertEquals(WorkflowImport.CREATED, workflows.import(org, first, "nina", dryRun = true))
        assertNull(repository.workflow(org, "release"), "A dry run writes nothing")

        assertEquals(WorkflowImport.CREATED, workflows.import(org, first, "nina", dryRun = false))
        assertEquals("import", signer.signed.single().first)
        assertEquals("nina", repository.lastDecisions(org).single().by)
        assertEquals(WorkflowImport.UNCHANGED, workflows.import(org, first, "nina", dryRun = false))
        assertEquals(1, repository.workflow(org, "release")?.version)

        val changed = raw(release(WorkflowStatus.ACCEPTED).copy(name = "Release acme-web"))
        assertEquals(WorkflowImport.UPDATED, workflows.import(org, changed, "nina", dryRun = true))
        assertEquals(1, repository.workflow(org, "release")?.version)
        assertEquals(WorkflowImport.UPDATED, workflows.import(org, changed, "nina", dryRun = false))
        assertEquals(2, repository.workflow(org, "release")?.version)
        assertEquals(listOf("import", "edit"), signer.signed.map { it.first })

        val invalid = assertFailsWith<OstromException> {
            workflows.import(org, JsonObject(mapOf("id" to JsonPrimitive("x"))), "nina", dryRun = true)
        }
        assertEquals("invalid_workflow", invalid.type)
    }
}
