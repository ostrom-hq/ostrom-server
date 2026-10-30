// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler

import dev.ostrom.core.api.models.NodeRun
import dev.ostrom.core.api.models.Workflow
import dev.ostrom.core.api.models.WorkflowNodeKind.CHECK
import dev.ostrom.core.api.models.WorkflowNodeKind.GATE
import dev.ostrom.core.api.models.WorkflowNodeKind.TRIGGER
import dev.ostrom.core.api.models.WorkflowStatus
import dev.ostrom.server.compiler.domain.models.CompilerSettings
import dev.ostrom.server.compiler.domain.services.PackageCompiler
import dev.ostrom.server.compiler.domain.usecases.CompileSourcesUseCaseImpl
import dev.ostrom.server.compiler.domain.usecases.GatesCatalogUseCaseImpl
import dev.ostrom.server.compiler.domain.usecases.PreviewPackageUseCaseImpl
import dev.ostrom.server.compiler.domain.usecases.PublishPackageUseCaseImpl
import dev.ostrom.server.compiler.domain.usecases.RevokePackageUseCaseImpl
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

class PackagesUseCasesTest {
    private val org = Uuid.parse("00000000-0000-0000-0000-00000000000a")
    private val other = Uuid.parse("00000000-0000-0000-0000-00000000000b")
    private val repository = MemoryCompilerRepository()
    private val sources = FixedSources()
    private val harness = CountingHarnessSigner()
    private val clock = SettableClock()
    private val compiler = CompileSourcesUseCaseImpl(repository, sources, harness, clock)
    private val publish = PublishPackageUseCaseImpl(
        compiler,
        repository,
        sources,
        SeedSigner(ByteArray(32) { it.toByte() }),
        clock,
    )
    private val revoke = RevokePackageUseCaseImpl(repository, clock)

    private val pushMain = on("push-main").on

    private suspend fun save(workflow: Workflow, orgId: Uuid = org) {
        val raw = PackageCompiler.json.encodeToJsonElement(Workflow.serializer(), workflow).jsonObject
        repository.saveWorkflow(orgId, workflow.id, raw, null, "dev-a", NOW)
        repository.addDecision(orgId, decision(workflow.id))
    }

    private fun tests(id: String, status: WorkflowStatus = WorkflowStatus.ACCEPTED, command: String = "make test") =
        workflow(
            id,
            listOf(
                node("t", TRIGGER, label = "push"),
                node("c", CHECK, label = "Tests before a push", run = NodeRun(command = command, on = pushMain)),
                node("g", GATE, label = "Push to main", approvers = listOf("lead")),
            ),
            chain("t", "c", "g"),
            status = status,
            name = "Release acme-api $id",
        )

    @Test
    fun aNewContentIsSignedOnceStoredAuditedAndPinnedAutomatically() = runTest {
        save(tests("w1"))
        save(tests("w2", WorkflowStatus.PROPOSED))

        val first = publish(org, "acme-api", "https://acme.example", "dev-a")
        clock.now += 5.minutes
        val again = publish(org, "acme-api", "https://acme.example", "dev-b")

        assertEquals(first.stored, again.stored)
        assertTrue(again.pinned)
        assertTrue(again.envelope.pinned)
        assertFalse(first.stored.envelope.pinned)
        val counts = first.stored.counts
        val names = listOf("workflows", "nodes", "scripts", "enforcedScripts", "notifications")
        assertEquals(listOf(2, 6, 2, 1, 0), names.map(counts::getValue))
        assertEquals(1, harness.signed.size, "a harness digest keeps its version")
        val audit = repository.auditLog(org, 10)
        assertEquals(listOf("package.pinned", "package.compiled"), audit.map { it.event })
        assertEquals("compiler (auto pin)", audit.first().actor)
        assertEquals(JsonPrimitive("acme-api"), audit.last().meta["repo"])
        assertEquals(JsonPrimitive(2), audit.last().meta["workflows"])
        assertTrue(audit.all { it.id.startsWith("aud_") && it.id.length == 16 })
    }

    @Test
    fun theAutomaticPinFollowsANewContentAndTheManualPinStays() = runTest {
        save(tests("w1"))
        val first = publish(org, null, "https://acme.example", "dev-a")
        save(tests("w1", command = "make check"))
        val second = publish(org, null, "https://acme.example", "dev-a")
        assertNotEquals(first.stored.envelope.contentDigest, second.stored.envelope.contentDigest)
        assertTrue(second.pinned)
        assertEquals(JsonNull, repository.auditLog(org, 1).single().meta["repo"])

        sources.settings = CompilerSettings(manualPin = true)
        save(tests("w1", command = "make all"))
        val third = publish(org, null, "https://acme.example", "dev-c")
        assertEquals(second.stored, third.stored, "a manual pin serves the pinned version")
        assertTrue(third.pinned)
        assertEquals(3, repository.packages(org, null).size)
    }

    @Test
    fun aManualPinIsSetByTheFirstPublisher() = runTest {
        sources.settings = CompilerSettings(manualPin = true)
        save(tests("w1"))
        publish(org, "acme-api", "https://acme.example", "dev-a")
        assertEquals("dev-a", repository.pin(org, "acme-api")?.by)
    }

    @Test
    fun aRevokedVersionLosesItsPinAndIsNeverPinnedAgainByAPublish() = runTest {
        save(tests("w1"))
        val served = publish(org, "acme-api", "https://acme.example", "dev-a")
        val digest = served.stored.envelope.digest

        val revoked = revoke.revoke(org, digest, "lead", "leaked token")
        assertTrue(revoked.revoked)
        assertEquals("lead", revoked.revokedBy)
        assertEquals("leaked token", revoked.revokeReason)
        assertNull(repository.pin(org, "acme-api"))

        val after = publish(org, "acme-api", "https://acme.example", "dev-a")
        assertTrue(after.stored.revoked)
        assertFalse(after.pinned)

        val restored = revoke.restore(org, served.stored.envelope.contentDigest, "lead")
        assertFalse(restored.revoked)
        assertNull(restored.revokedBy)
        assertEquals(
            listOf("package.restored", "package.revoked"),
            repository.auditLog(org, 2).map { it.event },
        )
        assertEquals(JsonPrimitive("leaked token"), repository.auditLog(org, 2).last().meta["reason"])
    }

    @Test
    fun revokingAVersionThatIsNotPinnedKeepsThePin() = runTest {
        save(tests("w1"))
        val old = publish(org, "acme-api", "https://acme.example", "dev-a")
        save(tests("w1", command = "make check"))
        val current = publish(org, "acme-api", "https://acme.example", "dev-a")

        revoke.revoke(org, old.stored.envelope.contentDigest, "lead", "old")

        assertEquals(current.stored.envelope.contentDigest, repository.pin(org, "acme-api")?.contentDigest)
    }

    @Test
    fun anUnknownOrAnotherOrganizationsPackageIsNotFound() = runTest {
        save(tests("w1"))
        val served = publish(org, "acme-api", "https://acme.example", "dev-a")
        val revokeOther = assertFailsWith<OstromException> {
            revoke.revoke(other, served.stored.envelope.digest, "x", "y")
        }
        assertEquals(ErrorKind.NOT_FOUND, revokeOther.kind)
        val restoreUnknown = assertFailsWith<OstromException> { revoke.restore(org, "nope", "x") }
        assertEquals(ErrorKind.NOT_FOUND, restoreUnknown.kind)
    }

    @Test
    fun thePreviewIsTheBodyWithItsVersionAndIsNeitherStoredNorSigned() = runTest {
        save(tests("w1"))
        sources.rules = listOf(rule("r1"))
        sources.ruleDecisions = listOf(decision("r1", kind = "rule"))
        sources.repos = listOf("acme-api", "acme-web")

        val preview = PreviewPackageUseCaseImpl(compiler)(org, "acme-api", "https://acme.example")

        assertEquals("ostrom.package/1", preview.getValue("format").jsonPrimitive.content)
        assertEquals("https://acme.example", preview.getValue("server").jsonPrimitive.content)
        assertTrue(preview.getValue("version").jsonPrimitive.content.startsWith("p202610300845-"))
        assertEquals("2026-10-30T08:45:00.000Z", preview.getValue("compiledAt").jsonPrimitive.content)
        assertTrue(repository.packages.isEmpty())
        assertEquals(listOf("r1"), harness.signed.single().second.map { it.rule.id })
    }

    @Test
    fun theCatalogueReadsTheSavedWorkflowsAndTheProductionApprover() = runTest {
        sources.settings = CompilerSettings(prodApprover = "ops")
        save(tests("w1"))
        val catalog = GatesCatalogUseCaseImpl(repository, sources)(org, "acme-api")
        val push = catalog.first { it.action.cls == "push-main" }
        assertEquals("w1", push.gate?.workflowId)
        assertEquals(listOf("lead"), push.gate?.approvers)
        assertTrue(GatesCatalogUseCaseImpl(repository, sources)(other, "acme-api").all { it.gate == null })
    }
}
