// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler

import dev.ostrom.core.api.models.NodeRun
import dev.ostrom.core.api.models.PackageEnvelope
import dev.ostrom.core.api.models.Workflow
import dev.ostrom.core.api.models.WorkflowNodeKind.CHECK
import dev.ostrom.core.api.models.WorkflowNodeKind.GATE
import dev.ostrom.core.api.models.WorkflowNodeKind.TRIGGER
import dev.ostrom.core.api.models.WorkflowStatus
import dev.ostrom.core.receipts.Digests
import dev.ostrom.server.compiler.domain.models.Authorship
import dev.ostrom.server.compiler.domain.models.CompilerSettings
import dev.ostrom.server.compiler.domain.models.DecisionDoc
import dev.ostrom.server.compiler.domain.models.PackageAudit
import dev.ostrom.server.compiler.domain.models.PackagePin
import dev.ostrom.server.compiler.domain.models.RuleDoc
import dev.ostrom.server.compiler.domain.repositories.CompilerRepository
import dev.ostrom.server.compiler.domain.services.CompilerSources
import dev.ostrom.server.compiler.domain.services.HarnessStamp
import dev.ostrom.server.compiler.domain.services.PackageCompiler
import dev.ostrom.server.compiler.presentation.ApproversGraphResponse
import dev.ostrom.server.compiler.presentation.AuditPage
import dev.ostrom.server.compiler.presentation.CompilerScopes
import dev.ostrom.server.compiler.presentation.PackageSummary
import dev.ostrom.server.compiler.presentation.PackagesPage
import dev.ostrom.server.compiler.presentation.PreviewRequest
import dev.ostrom.server.compiler.presentation.RevokeRequest
import dev.ostrom.server.compiler.presentation.SaveWorkflowRequest
import dev.ostrom.server.compiler.presentation.WorkflowsPage
import dev.ostrom.server.gates.GatesModule
import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.gates.domain.services.GateNodeResolver
import dev.ostrom.server.gates.presentation.GateScopes
import dev.ostrom.server.instance.InstanceModule
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.EnvironmentConfigSource
import dev.ostrom.server.kernel.config.LayeredConfigSource
import dev.ostrom.server.kernel.config.MapConfigSource
import dev.ostrom.server.kernel.signing.InstanceSigner
import dev.ostrom.server.receipts.ReceiptsModule
import dev.ostrom.server.receipts.domain.usecases.GetReceiptUseCase
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** The compiler against a real PostgreSQL and real HTTP: workflows, packages, the catalogue, and org isolation. */
class CompilerPostgresTest {
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val orgB = Uuid.parse("0000000b-0000-0000-0000-000000000000")

    // Tokens are `<kind>:<org letter>:<id>`: `user:a:lead` (browser session), `agent:a:launch-1`, `cli:a:dev` (the
    // CLI's token, which an agent can read on the developer's machine), `gates:a:x` (a gates-only key).
    private val cliScopes = setOf(CompilerScopes.WORKFLOWS_READ, CompilerScopes.WORKFLOWS_WRITE)

    private fun overrides(config: Map<String, String>?, sources: CompilerSources? = null): Module = module {
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) = when (token.substringBefore(':')) {
                    "user" -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf(GateScopes.ALL_USER))
                    "agent" -> AuthenticatedPrincipal(token, PrincipalKind.SERVICE, setOf(GateScopes.AGENT_SESSION))
                    "cli" -> AuthenticatedPrincipal(token, PrincipalKind.USER, cliScopes)
                    "gates" -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf(GateScopes.READ))
                    // A machine holding the packages scope: never a person.
                    "bot" -> AuthenticatedPrincipal(token, PrincipalKind.SERVICE, setOf(CompilerScopes.PACKAGES_WRITE))
                    else -> AuthenticatedPrincipal(token, PrincipalKind.USER, emptySet())
                }
            }
        }
        single<GateActorResolver> {
            object : GateActorResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal): GateActor? {
                    val (kind, org, id) = principal.subjectId.split(":").takeIf { it.size == 3 } ?: return null
                    val orgId = if (org == "a") orgA else orgB
                    return when (kind) {
                        "agent" -> GateActor("agent", orgId, ActorKind.AGENT_SESSION, launchId = id, personId = "lead")
                        "bot" -> GateActor(id, orgId, ActorKind.SERVICE)
                        else -> GateActor(id, orgId, ActorKind.USER, personId = id)
                    }
                }
            }
        }
        config?.let { values ->
            single<ConfigSource> { LayeredConfigSource(listOf(MapConfigSource(values), EnvironmentConfigSource())) }
        }
        sources?.let { fixed -> single<CompilerSources> { fixed } }
    }

    private fun compilerTest(
        config: Map<String, String>? = mapOf("OSTROM_PUBLIC_URL" to "https://ostrom.example/"),
        sources: CompilerSources? = null,
        block: suspend (TestServer) -> Unit,
    ) = serverTest(listOf(InstanceModule, ReceiptsModule, GatesModule, CompilerModule), overrides(config, sources)) {
        block(it)
    }

    private suspend fun TestServer.call(
        token: String?,
        path: String,
        method: String = "GET",
        body: Any? = null,
    ): HttpResponse {
        val request: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            when (body) {
                null -> Unit
                // A JSON tree has no serializer of its own once typed as Any: sent as its text.
                is JsonElement -> setBody(TextContent(body.toString(), ContentType.Application.Json))
                else -> {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
        }
        return when (method) {
            "POST" -> client.post(path, request)
            "PUT" -> client.put(path, request)
            "DELETE" -> client.delete(path, request)
            else -> client.get(path, request)
        }
    }

    private val pushMain = on("push-main").on

    private fun release(status: WorkflowStatus = WorkflowStatus.ACCEPTED, command: String = "make test"): JsonObject {
        val workflow = workflow(
            "release",
            listOf(
                node("t", TRIGGER, label = "push"),
                node("c", CHECK, label = "Tests before a push", run = NodeRun(command = command, on = pushMain)),
                node("g", GATE, label = "Push to main", approvers = listOf("lead")),
            ),
            chain("t", "c", "g"),
            status = status,
            name = "Release acme-api",
        )
        return PackageCompiler.json.encodeToJsonElement(Workflow.serializer(), workflow).jsonObject
    }

    @Test
    fun workflowsAreSavedWithTheirVersionAndOnlyByAPerson() = compilerTest { server ->
        assertEquals(HttpStatusCode.Forbidden, server.call("agent:a:l1", "/api/v1/workflows", "POST", release()).status)
        assertEquals(HttpStatusCode.Forbidden, server.call("nobody", "/api/v1/workflows").status)
        assertEquals(HttpStatusCode.Unauthorized, server.call(null, "/api/v1/workflows").status)

        val created = server.call("user:a:dev", "/api/v1/workflows", "POST", release(WorkflowStatus.PROPOSED))
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals(1, created.body<JsonObject>().getValue("version").jsonPrimitive.int)
        assertEquals(HttpStatusCode.Conflict, server.call("user:a:dev", "/api/v1/workflows", "POST", release()).status)

        val stale = SaveWorkflowRequest(release(), expectedVersion = 9)
        val path = "/api/v1/workflows/release"
        assertEquals(HttpStatusCode.Conflict, server.call("user:a:dev", path, "PUT", stale).status)
        val elsewhere = SaveWorkflowRequest(release(), expectedVersion = 1)
        val other = server.call("user:a:dev", "/api/v1/workflows/other", "PUT", elsewhere)
        assertEquals(HttpStatusCode.BadRequest, other.status)
        val noId = SaveWorkflowRequest(JsonObject(mapOf("name" to JsonPrimitive("x"))), 1)
        assertEquals(HttpStatusCode.BadRequest, server.call("user:a:dev", path, "PUT", noId).status)
        val arrayId = SaveWorkflowRequest(JsonObject(mapOf("id" to JsonArray(emptyList()))), 1)
        assertEquals(HttpStatusCode.BadRequest, server.call("user:a:dev", path, "PUT", arrayId).status)
        val saved = server.call("user:a:lead", path, "PUT", SaveWorkflowRequest(release(), 1))
        assertEquals(2, saved.body<JsonObject>().getValue("version").jsonPrimitive.int)
        assertEquals("lead", saved.body<JsonObject>().getValue("updatedBy").jsonPrimitive.content)

        val accepted = server.call("agent:a:l1", "/api/v1/workflows?status=accepted").body<WorkflowsPage>()
        assertEquals(listOf("release"), accepted.items.map { it.getValue("id").jsonPrimitive.content })
        assertTrue(server.call("user:a:dev", "/api/v1/workflows?status=proposed").body<WorkflowsPage>().items.isEmpty())
        assertEquals(HttpStatusCode.BadRequest, server.call("user:a:dev", "/api/v1/workflows?status=maybe").status)
        assertEquals(1, server.call("user:a:dev", "/api/v1/workflows?repo=acme-api").body<WorkflowsPage>().items.size)
        assertEquals(HttpStatusCode.OK, server.call("user:a:dev", "/api/v1/workflows/release").status)

        val decisions = server.koin.get<CompilerRepository>().decisions(orgA)
        assertEquals(listOf("dev", "lead"), decisions.map { it.by })
        val receipt = server.koin.get<GetReceiptUseCase>()(orgA, assertNotNull(decisions.last().receiptId))
        assertEquals("workflow", receipt.getValue("subject").jsonObject.getValue("kind").jsonPrimitive.content)
        val answer = receipt.getValue("decisions").jsonArray.single().jsonObject
        assertEquals("accept", answer.getValue("result").jsonPrimitive.content)

        assertEquals(HttpStatusCode.NotFound, server.call("user:b:dev", "/api/v1/workflows/release").status)
        assertEquals(HttpStatusCode.NotFound, server.call("user:b:dev", "/api/v1/workflows/release", "DELETE").status)
        assertEquals(HttpStatusCode.NoContent, server.call("user:a:dev", "/api/v1/workflows/release", "DELETE").status)
        assertEquals(HttpStatusCode.NotFound, server.call("user:a:dev", "/api/v1/workflows/release").status)
    }

    @Test
    fun theCliTokenProposesButOnlyABrowserSessionDecides() = compilerTest { server ->
        // SECURITY-AUDIT S2-03, S2-04: an agent that reads the CLI's token cannot accept or remove a gate.
        val gatesOnly = server.call("gates:a:x", "/api/v1/workflows")
        assertEquals(HttpStatusCode.Forbidden, gatesOnly.status)
        assertTrue(gatesOnly.bodyAsText().contains("does not allow workflows:read"))
        assertEquals(HttpStatusCode.Forbidden, server.call("gates:a:x", "/api/v1/gates/catalog").status)
        val revoke = server.call("cli:a:dev", "/api/v1/packages/x/revoke", "POST", RevokeRequest("x"))
        assertEquals(HttpStatusCode.Forbidden, revoke.status)
        val machine = server.call("bot:a:ci", "/api/v1/packages/x/revoke", "POST", RevokeRequest("x"))
        assertTrue(machine.bodyAsText().contains("Only a person changes workflows and packages."))
        val orgless = server.call("user:nobody", "/api/v1/workflows")
        assertEquals(HttpStatusCode.Forbidden, orgless.status)
        assertTrue(orgless.bodyAsText().contains("This credential belongs to no organization."))

        val accepted = server.call("cli:a:dev", "/api/v1/workflows", "POST", release())
        assertEquals(HttpStatusCode.Forbidden, accepted.status)
        assertTrue(accepted.bodyAsText().contains("Only a person signed in to the console"))
        val noStatus = JsonObject(mapOf("id" to JsonPrimitive("bare")))
        assertEquals(HttpStatusCode.Forbidden, server.call("cli:a:dev", "/api/v1/workflows", "POST", noStatus).status)
        val proposed = server.call("cli:a:dev", "/api/v1/workflows", "POST", release(WorkflowStatus.PROPOSED))
        assertEquals(HttpStatusCode.Created, proposed.status)
        val path = "/api/v1/workflows/release"
        val accepting = SaveWorkflowRequest(release(), expectedVersion = 1)
        assertEquals(HttpStatusCode.Forbidden, server.call("cli:a:dev", path, "PUT", accepting).status)
        val reproposed = SaveWorkflowRequest(release(WorkflowStatus.PROPOSED, command = "make check"), 1)
        assertEquals(HttpStatusCode.OK, server.call("cli:a:dev", path, "PUT", reproposed).status)
        assertEquals(HttpStatusCode.Forbidden, server.call("cli:a:dev", path, "DELETE").status)

        val accept = server.call("user:a:lead", path, "PUT", SaveWorkflowRequest(release(), 2))
        assertEquals(HttpStatusCode.OK, accept.status)
        val edit = SaveWorkflowRequest(release(WorkflowStatus.PROPOSED), 3)
        assertEquals(HttpStatusCode.Forbidden, server.call("cli:a:dev", path, "PUT", edit).status, "Accepted stays so")
        assertEquals(HttpStatusCode.OK, server.call("cli:a:dev", "/api/v1/gates/catalog?repo=acme-api").status)
        assertEquals(HttpStatusCode.OK, server.call("cli:a:dev", path).status)
    }

    @Test
    fun aPackageIsSignedPinnedListedRevokedAndRestored() = compilerTest { server ->
        server.call("user:a:lead", "/api/v1/workflows", "POST", release())

        val envelope = server.call("agent:a:l1", "/api/v1/packages/current?repo=acme-api").body<PackageEnvelope>()
        val signer = server.koin.get<InstanceSigner>()
        val bytes = envelope.payload.encodeToByteArray()
        assertEquals(Digests.sha256Hex(bytes), envelope.digest)
        assertTrue(signer.verify(bytes, Base64.decode(envelope.signature)))
        assertTrue(envelope.pinned)
        assertEquals("acme-api", envelope.repo)
        assertTrue("\"server\":\"https://ostrom.example\"" in envelope.payload)
        val again = server.call("agent:a:l2", "/api/v1/packages/current?repo=acme-api").body<PackageEnvelope>()
        assertEquals(envelope, again)

        val listed = server.call("user:a:lead", "/api/v1/packages?repo=acme-api").body<PackagesPage>().items.single()
        assertTrue(listed.pinned)
        assertEquals(1, listed.counts.getValue("enforcedScripts"))
        val byDigest = server.call("user:a:lead", "/api/v1/packages/${envelope.digest}").body<PackageEnvelope>()
        assertEquals(envelope, byDigest)
        val status = server.call("user:a:x", "/api/v1/packages/${envelope.contentDigest}/status")
        assertEquals(listed, status.body<PackageSummary>())
        assertEquals(HttpStatusCode.NotFound, server.call("user:a:lead", "/api/v1/packages/nope").status)
        assertEquals(HttpStatusCode.NotFound, server.call("user:b:lead", "/api/v1/packages/${envelope.digest}").status)
        assertTrue(server.call("user:b:lead", "/api/v1/packages").body<PackagesPage>().items.isEmpty())
        assertEquals(HttpStatusCode.Conflict, server.call("user:a:lead", "/api/v1/workflows/release", "DELETE").status)

        val revokePath = "/api/v1/packages/${envelope.digest}/revoke"
        assertEquals(HttpStatusCode.Forbidden, server.call("agent:a:l1", revokePath, "POST", RevokeRequest("x")).status)
        val revoked = server.call("user:a:lead", revokePath, "POST", RevokeRequest("leaked")).body<PackageSummary>()
        assertTrue(revoked.revoked)
        assertFalse(revoked.pinned)
        assertEquals("leaked", revoked.revokeReason)
        val restorePath = "/api/v1/packages/${envelope.digest}/restore"
        val restored = server.call("user:a:lead", restorePath, "POST").body<PackageSummary>()
        assertFalse(restored.revoked)
        assertNull(restored.revokedBy)

        val audit = server.call("user:a:lead", "/api/v1/packages/audit?limit=500").body<AuditPage>().items
        val events = listOf("package.restored", "package.revoked", "package.pinned", "package.compiled")
        assertEquals(events, audit.map { it.event })
        assertEquals(1, server.call("user:a:lead", "/api/v1/packages/audit?limit=1").body<AuditPage>().items.size)
        assertEquals(4, server.call("user:a:lead", "/api/v1/packages/audit").body<AuditPage>().items.size)
        assertTrue(server.call("user:b:lead", "/api/v1/packages/audit").body<AuditPage>().items.isEmpty())

        val preview = server.call("user:a:lead", "/api/v1/packages/preview", "POST", PreviewRequest("acme-api"))
            .body<JsonObject>()
        assertEquals(envelope.contentDigest, preview.getValue("contentDigest").jsonPrimitive.content)
    }

    @Test
    fun withoutAPublicUrlSessionsCallBackTheRequestsOrigin() = compilerTest(config = null) { server ->
        server.call("user:a:lead", "/api/v1/workflows", "POST", release())
        val envelope = server.call("agent:a:l1", "/api/v1/packages/current").body<PackageEnvelope>()
        assertTrue("\"server\":\"http://localhost" in envelope.payload, envelope.payload.take(300))
        assertNull(envelope.repo)
    }

    @Test
    fun theCatalogueAndTheGatesModuleSeeTheSameGate() = compilerTest(
        sources = object : CompilerSources {
            override suspend fun rules(orgId: Uuid): List<RuleDoc> = listOf(rule("r1"), rule("r2", status = "proposed"))
            override suspend fun ruleDecisions(orgId: Uuid): List<DecisionDoc> = emptyList()
            override suspend fun repos(orgId: Uuid): List<String> = listOf("acme-api")
            override suspend fun settings(orgId: Uuid) = CompilerSettings(prodApprover = "ops")
        },
    ) { server ->
        server.call("user:a:lead", "/api/v1/workflows", "POST", release())

        val catalog = server.call("agent:a:l1", "/api/v1/gates/catalog?repo=acme-api").body<JsonObject>()
        assertEquals("closed", catalog.getValue("failMode").jsonPrimitive.content)
        val push = catalog.getValue("actions").jsonArray.map { it.jsonObject }
            .first { it.getValue("class").jsonPrimitive.content == "push-main" }
        assertEquals("release", push.getValue("gate").jsonObject.getValue("workflowId").jsonPrimitive.content)
        assertEquals(1, (catalog.getValue("gates") as JsonArray).size)

        // S2-20: the hooks trust the same catalogue only signed by the instance, for this repository, for minutes.
        val signedPath = "/api/v1/gates/catalog/signed?repo=acme-api"
        val policy = server.call("agent:a:l1", signedPath).body<JsonObject>()
        val signed = policy.getValue("payload").jsonPrimitive.content
        val signature = Base64.decode(policy.getValue("signature").jsonPrimitive.content)
        assertTrue(server.koin.get<InstanceSigner>().verify(signed.encodeToByteArray(), signature))
        val body = Json.parseToJsonElement(signed).jsonObject
        assertEquals("acme-api", body.getValue("repo").jsonPrimitive.content)
        assertEquals(catalog.getValue("actions"), body.getValue("actions"))
        val otherOrg = server.call("agent:b:l1", signedPath).body<JsonObject>().getValue("payload").jsonPrimitive
        val otherGates = Json.parseToJsonElement(otherOrg.content).jsonObject.getValue("gates").jsonArray
        assertTrue(otherGates.isEmpty(), "org B's own policy")
        assertEquals(HttpStatusCode.BadRequest, server.call("agent:a:l1", "/api/v1/gates/catalog/signed").status)
        assertEquals(HttpStatusCode.Unauthorized, server.call(null, signedPath).status)
        assertEquals(HttpStatusCode.Forbidden, server.call("gates:a:x", signedPath).status)

        // Who approves what, from the same catalogue: the lead answers the release gate, for a push to main.
        val graphPath = "/api/v1/gates/approvers?repo=acme-api"
        val graph = server.call("user:a:lead", graphPath).body<ApproversGraphResponse>()
        val lead = graph.approvers.single()
        assertEquals("lead", lead.approver)
        assertEquals(listOf("release"), lead.gates.map { it.workflowId })
        assertTrue("push-main" in lead.gates.single().actions)
        assertTrue(graph.unanswered.isEmpty())
        val ungated = catalog.getValue("actions").jsonArray.map { it.jsonObject }
            .filter { it["gate"] == null || it["gate"] is JsonNull }
            .map { it.getValue("class").jsonPrimitive.content }
        assertEquals(ungated.distinct().sorted(), graph.ungated, "the catalogue's actions without a gate")
        val elsewhere = server.call("user:b:lead", graphPath).body<ApproversGraphResponse>()
        assertTrue(elsewhere.approvers.isEmpty(), "org B sees nobody of org A")
        assertEquals(HttpStatusCode.Unauthorized, server.call(null, graphPath).status)
        assertEquals(HttpStatusCode.Forbidden, server.call("gates:a:x", graphPath).status)

        val resolver = server.koin.get<GateNodeResolver>()
        val gate = assertNotNull(resolver.resolve(orgA, "acme-api", "push-main"))
        assertEquals(listOf("lead"), gate.approvers)
        assertTrue(gate.enforced)
        assertNull(resolver.resolve(orgA, "acme-api", "no-such-class"))
        assertNull(resolver.resolve(orgB, "acme-api", "push-main"))

        val envelope = server.call("agent:a:l1", "/api/v1/packages/current?repo=acme-api").body<PackageEnvelope>()
        val payload = PackageCompiler.json.parseToJsonElement(envelope.payload).jsonObject
        val harness = payload.getValue("harness").jsonObject
        val receipt = server.koin.get<GetReceiptUseCase>()(orgA, harness.getValue("receiptId").jsonPrimitive.content)
        assertEquals("harness", receipt.getValue("subject").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals("ostrom compiler", receipt.getValue("initiator").jsonPrimitive.content)
        val cited = receipt.getValue("rules").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(listOf("r1"), cited)
    }

    @Test
    fun theRepositoryKeepsEveryFieldAndTheFirstVersionOfAContent() = compilerTest { server ->
        val repository = server.koin.get<CompilerRepository>()
        val first = HarnessStamp("h1", "rcpt_1")
        assertEquals(first, repository.saveHarness(orgA, null, "d", first))
        assertEquals(first, repository.saveHarness(orgA, null, "d", HarnessStamp("h2", null)))
        assertEquals(first, repository.harness(orgA, null, "d"))
        assertNull(repository.harness(orgA, "acme-api", "d"))
        assertNull(repository.harness(orgB, null, "d"))

        val decision = DecisionDoc("dec_1", "workflow", "w", "lead", "2026-10-30T08:00:00.000Z", null)
        repository.addDecision(orgA, decision)
        assertEquals(listOf(decision), repository.decisions(orgA))

        val meta = JsonObject(mapOf("repo" to JsonPrimitive("acme-api")))
        val line = PackageAudit("aud_1", "2026-10-30T08:00:00.000Z", "package.pinned", "lead", "p1", meta)
        repository.audit(orgA, line)
        assertEquals(listOf(line), repository.auditLog(orgA, 10))

        val pin = PackagePin("cd", "lead", NOW)
        repository.setPin(orgA, "acme-api", pin)
        repository.setPin(orgA, "acme-api", pin.copy(by = "ops"))
        assertEquals("ops", repository.pin(orgA, "acme-api")?.by)
        repository.setPin(orgA, "acme-api", null)
        assertNull(repository.pin(orgA, "acme-api"))
        assertNull(repository.saveWorkflow(orgA, "w", release(), 3, Authorship("lead", NOW)), "no such version")
        assertFalse(repository.deleteWorkflow(orgA, "w"))
    }

    @Test
    fun rowLevelSecurityHidesOtherOrganizationsEvenWithoutAWhereClause() = compilerTest { server ->
        server.call("user:a:lead", "/api/v1/workflows", "POST", release())
        server.call("agent:a:l1", "/api/v1/packages/current?repo=acme-api")
        // The CI database user is a superuser, which bypasses row level security: the proof runs as a plain role.
        server.driver.execute(
            "DO \$\$ BEGIN CREATE ROLE ostrom_rls_probe NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END \$\$",
        ).getOrThrow()
        val tables = listOf(
            "compiler_workflows",
            "compiler_decisions",
            "compiler_harnesses",
            "compiler_packages",
            "compiler_pins",
            "compiler_audit",
        )
        server.driver.execute("GRANT SELECT, INSERT ON ${tables.joinToString()} TO ostrom_rls_probe").getOrThrow()
        val counts = server.driver.transaction {
            execute("SET LOCAL ROLE ostrom_rls_probe").getOrThrow()
            suspend fun count(org: Uuid?): List<String> {
                org?.let {
                    val scope = Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)")
                    fetchAll(scope.bind("org", it.toString())).getOrThrow()
                }
                return tables.map { fetchAll("SELECT COUNT(*) AS n FROM $it").getOrThrow().first().get("n").asString() }
            }
            listOf(count(null), count(orgB), count(orgA))
        }
        assertTrue(counts[0].all { it == "0" }, "No org set: nothing")
        assertTrue(counts[1].all { it == "0" }, "Org B: nothing")
        assertTrue(counts[2].all { it != "0" }, "Org A: its rows in every table, $counts")
        val forged = server.driver.transaction {
            execute("SET LOCAL ROLE ostrom_rls_probe").getOrThrow()
            fetchAll(Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)").bind("org", orgB.toString()))
                .getOrThrow()
            execute(
                Statement.create(
                    "INSERT INTO compiler_pins (org_id, repo_key, content_digest, pinned_by, pinned_at) " +
                        "SELECT :org, 'forged', content_digest, 'x', NOW() FROM compiler_packages LIMIT 1",
                ).bind("org", orgA),
            )
        }
        assertEquals(0L, forged.getOrThrow(), "Org B can neither see nor write org A's rows")
    }
}
