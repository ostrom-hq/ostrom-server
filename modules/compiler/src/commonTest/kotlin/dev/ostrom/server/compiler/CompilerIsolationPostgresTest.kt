// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler

import dev.ostrom.core.api.models.NodeRun
import dev.ostrom.core.api.models.PackageEnvelope
import dev.ostrom.core.api.models.Workflow
import dev.ostrom.core.api.models.WorkflowNodeKind.CHECK
import dev.ostrom.core.api.models.WorkflowNodeKind.GATE
import dev.ostrom.core.api.models.WorkflowNodeKind.TRIGGER
import dev.ostrom.core.api.models.WorkflowStatus
import dev.ostrom.server.compiler.domain.services.PackageCompiler
import dev.ostrom.server.compiler.presentation.CompilerScopes
import dev.ostrom.server.compiler.presentation.PackageSummary
import dev.ostrom.server.compiler.presentation.RevokeRequest
import dev.ostrom.server.compiler.presentation.SaveWorkflowRequest
import dev.ostrom.server.compiler.presentation.WorkflowsPage
import dev.ostrom.server.gates.GatesModule
import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.gates.presentation.GateScopes
import dev.ostrom.server.instance.InstanceModule
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalChannel
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.EnvironmentConfigSource
import dev.ostrom.server.kernel.config.LayeredConfigSource
import dev.ostrom.server.kernel.config.MapConfigSource
import dev.ostrom.server.receipts.ReceiptsModule
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Another organization neither sees nor changes the workflows, packages and gates catalogue of the organization that
 * made them: its lists, catalogue and current package are its own, every route on one of them answers 404 (409 for a
 * console save, which finds no version of it to replace), and the owner reads them unchanged.
 */
class CompilerIsolationPostgresTest {
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val orgB = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    private val cliScopes = setOf(CompilerScopes.WORKFLOWS_READ, CompilerScopes.WORKFLOWS_WRITE)

    // Tokens are `<kind>:<org letter>:<id>`, as in CompilerPostgresTest: `user:a:lead` (browser session),
    // `agent:a:launch-1`, `cli:b:dev` (the CLI's token, which proposes but never decides).
    private val overrides = module {
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) = when (token.substringBefore(':')) {
                    "user" -> AuthenticatedPrincipal(
                        token,
                        PrincipalKind.USER,
                        setOf(GateScopes.ALL_USER),
                        channel = PrincipalChannel.BROWSER,
                    )
                    "agent" -> AuthenticatedPrincipal(token, PrincipalKind.SERVICE, setOf(GateScopes.AGENT_SESSION))
                    "cli" -> AuthenticatedPrincipal(token, PrincipalKind.USER, cliScopes)
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
                        // Accepting a workflow needs an owner or an admin since #98 (H4): the person carries its role.
                        else -> GateActor(id, orgId, ActorKind.USER, personId = id, role = roleOf(id))
                    }
                }
            }
        }
        single<ConfigSource> {
            val publicUrl = MapConfigSource(mapOf("OSTROM_PUBLIC_URL" to "https://ostrom.example/"))
            LayeredConfigSource(listOf(publicUrl, EnvironmentConfigSource()))
        }
    }

    private fun compilerTest(block: suspend (TestServer) -> Unit) =
        serverTest(listOf(InstanceModule, ReceiptsModule, GatesModule, CompilerModule), overrides) { block(it) }

    private suspend fun TestServer.call(token: String, path: String, method: String = "GET", body: Any? = null) =
        when (method) {
            "POST" -> client.post(path) { send(token, body) }
            "PUT" -> client.put(path) { send(token, body) }
            else -> client.get(path) { send(token, null) }
        }

    private fun io.ktor.client.request.HttpRequestBuilder.send(token: String, body: Any?) {
        header(HttpHeaders.Authorization, "Bearer $token")
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

    private suspend fun HttpResponse.workflowItems(): List<JsonObject> = body<WorkflowsPage>().items

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
    fun anotherOrganizationNeitherSeesNorChangesTheWorkflowsPackagesAndCatalogue() = compilerTest { server ->
        assertEquals(HttpStatusCode.Created, server.call("user:a:lead", "/api/v1/workflows", "POST", release()).status)
        val envelope = server.call("agent:a:l1", "/api/v1/packages/current?repo=acme-api").body<PackageEnvelope>()
        val workflowPath = "/api/v1/workflows/release"
        val workflowBefore = server.call("user:a:lead", workflowPath).body<JsonObject>()
        val statusPath = "/api/v1/packages/${envelope.contentDigest}/status"
        val packageBefore = server.call("user:a:lead", statusPath).body<PackageSummary>()
        assertTrue(packageBefore.pinned && !packageBefore.revoked)

        // The lists, the catalogue and the current package of org B are org B's own.
        assertTrue(server.call("user:b:lead", "/api/v1/workflows").workflowItems().isEmpty(), "B lists none of A's")
        assertTrue(server.call("agent:b:l1", "/api/v1/workflows").workflowItems().isEmpty())
        val catalog = server.call("agent:b:l1", "/api/v1/gates/catalog?repo=acme-api").body<JsonObject>()
        assertEquals(0, (catalog.getValue("gates") as JsonArray).size, "No gate of A in B's catalogue")
        val gated = catalog.getValue("actions").jsonArray.map { it.jsonObject }
            .filter { it["gate"] != null && it["gate"] !is JsonNull }
        assertEquals(emptyList(), gated, "No action of B is covered by A's gate")
        val current = server.call("agent:b:l1", "/api/v1/packages/current?repo=acme-api").body<PackageEnvelope>()
        assertNotEquals(envelope.contentDigest, current.contentDigest, "B is served its own package, not A's")
        assertNotEquals(envelope.digest, current.digest)

        // Every route on A's workflow and package.
        assertEquals(HttpStatusCode.NotFound, server.call("user:b:lead", workflowPath).status)
        val proposal = SaveWorkflowRequest(release(WorkflowStatus.PROPOSED, command = "make evil"), expectedVersion = 1)
        assertEquals(HttpStatusCode.NotFound, server.call("cli:b:dev", workflowPath, "PUT", proposal).status)
        val decision = SaveWorkflowRequest(release(command = "make evil"), expectedVersion = 1)
        assertEquals(
            HttpStatusCode.NotFound,
            server.call("user:b:lead", workflowPath, "PUT", decision).status,
            "B's console finds no workflow of that id in B",
        )
        // Without a version, a PUT never creates B's own copy of the id (nor answers a key conflict that would tell
        // A's exists): 404, and B still has no such workflow.
        val unversioned = SaveWorkflowRequest(release(command = "make evil"))
        assertEquals(HttpStatusCode.NotFound, server.call("user:b:lead", workflowPath, "PUT", unversioned).status)
        assertEquals(HttpStatusCode.NotFound, server.call("user:b:lead", workflowPath).status, "Nothing was created")
        assertEquals(HttpStatusCode.NotFound, server.call("user:b:lead", statusPath).status)
        val revokePath = "/api/v1/packages/${envelope.digest}/revoke"
        val restorePath = "/api/v1/packages/${envelope.digest}/restore"
        assertEquals(HttpStatusCode.NotFound, server.call("user:b:lead", revokePath, "POST", RevokeRequest("x")).status)
        assertEquals(packageBefore, server.call("user:a:lead", statusPath).body<PackageSummary>(), "Not revoked by B")

        val revoked = server.call("user:a:lead", revokePath, "POST", RevokeRequest("leaked")).body<PackageSummary>()
        assertTrue(revoked.revoked)
        assertEquals(HttpStatusCode.NotFound, server.call("user:b:lead", restorePath, "POST").status)
        assertEquals(revoked, server.call("user:a:lead", statusPath).body<PackageSummary>(), "Not restored by B")

        assertEquals(workflowBefore, server.call("user:a:lead", workflowPath).body<JsonObject>(), "Unchanged by B")
        val listed = server.call("user:a:lead", "/api/v1/workflows").workflowItems()
        assertEquals(listOf(workflowBefore), listed, "A still lists its workflow, unchanged")
    }

    private suspend fun TestServer.gatedActions(token: String, repo: String): List<String> =
        call(token, "/api/v1/gates/catalog?repo=$repo").body<JsonObject>().getValue("actions").jsonArray
            .map { it.jsonObject }
            .filter { it["gate"] != null && it["gate"] !is JsonNull }
            .map { it.getValue("class").toString() }

    /**
     * A star in `repos` (R6) stands for any owner, and still never reaches another organization: the console writes
     * one for the owner of a repository of the organization, and a session of B in a repository of the same name is
     * served B's catalogue only. The organization bounds the workflows read, before any repository is matched.
     */
    @Test
    fun aWorkflowLimitedToAnyOwnerOfANameNeverReachesAnotherOrganization() = compilerTest { server ->
        val limited = JsonObject(release() + ("repos" to JsonArray(listOf(JsonPrimitive("*/web")))))
        assertEquals(HttpStatusCode.Created, server.call("user:a:lead", "/api/v1/workflows", "POST", limited).status)

        assertTrue(server.gatedActions("agent:a:l1", "acme/web").isNotEmpty(), "A's own web repository is gated")
        assertEquals(emptyList(), server.gatedActions("agent:a:l1", "acme/api"), "Outside its repos, nothing")
        assertEquals(emptyList(), server.gatedActions("agent:b:l1", "acme/web"), "B, same owner and name: nothing")
        assertEquals(emptyList(), server.gatedActions("agent:b:l1", "umbra/web"), "B, its own web: nothing")
        val a = server.call("agent:a:l1", "/api/v1/packages/current?repo=acme/web").body<PackageEnvelope>()
        val b = server.call("agent:b:l1", "/api/v1/packages/current?repo=acme/web").body<PackageEnvelope>()
        assertNotEquals(a.contentDigest, b.contentDigest, "B's package for the same name is not A's")
    }
}
