// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler

import dev.ostrom.core.api.models.NodePosition
import dev.ostrom.core.api.models.NodeRun
import dev.ostrom.core.api.models.Workflow
import dev.ostrom.core.api.models.WorkflowNodeKind.CHECK
import dev.ostrom.core.api.models.WorkflowNodeKind.GATE
import dev.ostrom.core.api.models.WorkflowNodeKind.NOTIFY
import dev.ostrom.core.api.models.WorkflowNodeKind.STEP
import dev.ostrom.core.api.models.WorkflowStatus
import dev.ostrom.server.compiler.domain.models.PackagePin
import dev.ostrom.server.compiler.domain.services.PackageCompiler
import dev.ostrom.server.compiler.domain.usecases.CompileSourcesUseCaseImpl
import dev.ostrom.server.compiler.domain.usecases.PublishPackageUseCaseImpl
import dev.ostrom.server.compiler.domain.usecases.RevokePackageUseCaseImpl
import dev.ostrom.server.compiler.domain.usecases.WorkflowsUseCaseImpl
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
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
    private val workflows = WorkflowsUseCaseImpl(repository, signer, clock)

    private fun raw(workflow: Workflow): JsonObject =
        PackageCompiler.json.encodeToJsonElement(Workflow.serializer(), workflow).jsonObject

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
        val decision = repository.decisions(org).single()
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
    fun aWorkflowCompiledInAPinnedPackageCannotBeDeleted() = runTest {
        val sources = FixedSources()
        val compiler = CompileSourcesUseCaseImpl(repository, sources, CountingHarnessSigner(), clock)
        val signer = SeedSigner(ByteArray(32))
        val publish = PublishPackageUseCaseImpl(compiler, repository, sources, signer, clock)
        workflows.save(org, raw(release(WorkflowStatus.ACCEPTED)), null, "lead", create = true)
        workflows.save(org, raw(workflow("web", listOf(node("s", STEP)), name = "Website")), null, "lead", true)
        val served = publish(org, "acme-api", "https://acme.example", "lead")

        expectFailure(ErrorKind.CONFLICT) { workflows.delete(org, "release") }
        workflows.delete(org, "web")
        assertNull(repository.workflow(org, "web"))

        RevokePackageUseCaseImpl(repository, clock).revoke(org, served.stored.envelope.digest, "lead", "gone")
        workflows.delete(org, "release")
        assertTrue(repository.workflows(org).isEmpty())
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
}
