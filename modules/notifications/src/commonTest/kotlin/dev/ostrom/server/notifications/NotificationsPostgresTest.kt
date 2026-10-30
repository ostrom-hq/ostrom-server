// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications

import dev.ostrom.server.gates.GatesModule
import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.gates.domain.services.GateNodeResolver
import dev.ostrom.server.gates.domain.services.GateReceiptSigner
import dev.ostrom.server.gates.domain.services.GateSessions
import dev.ostrom.server.gates.presentation.GateAskRequest
import dev.ostrom.server.gates.presentation.GatePush
import dev.ostrom.server.gates.presentation.GatePushRef
import dev.ostrom.server.gates.presentation.GateScopes
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.notifications.domain.EmailSender
import dev.ostrom.server.notifications.domain.PersonEmails
import dev.ostrom.server.notifications.domain.PushDevicesRepository
import dev.ostrom.server.notifications.domain.PushSender
import dev.ostrom.server.notifications.presentation.PushDeviceDto
import dev.ostrom.server.notifications.presentation.PushDeviceRequest
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Phones and emails against a real PostgreSQL and real HTTP: a request reaches its approver, orgs stay apart. */
class NotificationsPostgresTest {
    private val push = RecordingPush()
    private val email = RecordingEmail()

    // Tokens are `<kind>:<org letter>:<id>`: `user:a:rev-b` (a person), `agent:a:<session>`, `reader:a:ops`.
    private val overrides = module {
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) = when (token.substringBefore(':')) {
                    "user" -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf(GateScopes.ALL_USER))
                    "app" -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf(GateScopes.ANSWER))
                    "agent" -> AuthenticatedPrincipal(token, PrincipalKind.SERVICE, setOf(GateScopes.AGENT_SESSION))
                    else -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf(GateScopes.READ))
                }
            }
        }
        single<GateActorResolver> {
            object : GateActorResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal): GateActor? {
                    val (kind, org, id) = principal.subjectId.split(":").takeIf { it.size == 3 } ?: return null
                    val orgId = if (org == "a") ORG else OTHER_ORG
                    return if (kind == "agent") {
                        GateActor("agent", orgId, ActorKind.AGENT_SESSION, launchId = id, personId = "lyes")
                    } else {
                        GateActor(id, orgId, ActorKind.USER, personId = id)
                    }
                }
            }
        }
        single<GateSessions> {
            object : GateSessions {
                override suspend fun assertOpen(orgId: Uuid, sessionId: String, launchId: String?) = Unit
            }
        }
        single<GateNodeResolver> {
            object : GateNodeResolver {
                override suspend fun resolve(orgId: Uuid, repo: String?, actionClass: String) =
                    GateNode("ship", "g-main", "Merge into main", listOf("lyes", "rev-b", "rev-c"), enforced = true)
            }
        }
        single<GateReceiptSigner> {
            object : GateReceiptSigner {
                override suspend fun sign(gate: Gate): String = "rcpt_${gate.id}"
            }
        }
        single<PushSender> { push }
        single<EmailSender> { email }
        single<PersonEmails> { MapEmails(mapOf("rev-c" to "rev-c@acme.dev")) }
    }

    private fun notificationsTest(block: suspend (TestServer) -> Unit) =
        serverTest(listOf(GatesModule, NotificationsModule), overrides) { server -> block(server) }

    private suspend fun TestServer.register(token: String?, body: PushDeviceRequest = phone()): HttpResponse =
        client.post("/api/v1/push-devices") {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun TestServer.remove(token: String?, id: String): HttpResponse =
        client.delete("/api/v1/push-devices/$id") { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

    private fun phone(token: String = TOKEN) = PushDeviceRequest(token, "expo", "ios", "fr-FR")

    private suspend fun TestServer.ask() = client.post("/api/v1/gates") {
        header(HttpHeaders.Authorization, "Bearer agent:a:claude-5e5")
        contentType(ContentType.Application.Json)
        setBody(
            GateAskRequest(
                sessionId = "claude-5e5",
                repo = "acme/api",
                cls = "push-main",
                command = "git push origin main",
                dev = "lyes",
                push = GatePush("git@github.com:acme/api.git", listOf(GatePushRef("refs/heads/main", "a".repeat(40)))),
                diff = "+x",
            ),
        )
    }

    @Test
    fun aRequestReachesThePhonesAndTheAddressOfItsPendingApprovers() = notificationsTest { server ->
        val created = server.register("app:a:rev-b")
        assertEquals(HttpStatusCode.Created, created.status)
        val device = created.body<PushDeviceDto>()
        assertEquals(listOf("expo", "ios", "fr-FR"), listOf(device.provider, device.platform, device.locale))
        assertFalse(created.bodyAsText().contains(TOKEN), "a token is never returned")
        assertEquals(device.id, server.register("app:a:rev-b").body<PushDeviceDto>().id, "registered again: kept")

        server.ask()
        val sent = push.batches.single()
        assertEquals(listOf(TOKEN), sent.map { it.to })
        assertEquals("approval", sent.single().data["kind"])
        assertEquals(listOf("rev-c@acme.dev"), email.sent.map { it.to })

        assertEquals(HttpStatusCode.NoContent, server.remove("user:a:rev-b", device.id).status)
        assertEquals(HttpStatusCode.NotFound, server.remove("user:a:rev-b", device.id).status)
        assertEquals(HttpStatusCode.NotFound, server.remove("user:a:rev-b", "not-a-uuid").status)
    }

    @Test
    fun onlyAPersonRegistersAPhone() = notificationsTest { server ->
        assertEquals(HttpStatusCode.Unauthorized, server.register(null).status)
        assertEquals(HttpStatusCode.Unauthorized, server.remove(null, Uuid.random().toString()).status)
        assertEquals(HttpStatusCode.Forbidden, server.register("agent:a:claude").status, "an agent")
        assertEquals(HttpStatusCode.Forbidden, server.register("reader:a:ops").status, "a read-only credential")
        assertEquals(HttpStatusCode.Forbidden, server.register("user:a").status, "no organization")
        val bad = server.register("user:a:rev-b", phone("ExponentPushToken[x]"))
        assertEquals(HttpStatusCode.BadRequest, bad.status)
    }

    @Test
    fun anotherOrganizationNeitherSeesNorRemovesAPhone() = notificationsTest { server ->
        val device = server.register("user:a:rev-b").body<PushDeviceDto>()
        assertEquals(HttpStatusCode.NotFound, server.remove("user:b:rev-b", device.id).status)
        val repository = server.koin.get<PushDevicesRepository>()
        assertTrue(repository.devices(OTHER_ORG, "rev-b").isEmpty())
        assertEquals(1, repository.devices(ORG, "rev-b").size)
        // The same token in another organization is that organization's own row.
        assertEquals(HttpStatusCode.Created, server.register("user:b:rev-b").status)
        assertEquals(1, repository.devices(OTHER_ORG, "rev-b").size)

        server.driver.execute(
            "DO \$\$ BEGIN CREATE ROLE ostrom_rls_probe NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END \$\$",
        ).getOrThrow()
        server.driver.execute("GRANT SELECT, INSERT ON notifications_push_devices TO ostrom_rls_probe").getOrThrow()
        val seen = server.driver.transaction {
            execute("SET LOCAL ROLE ostrom_rls_probe").getOrThrow()
            val none = count()
            setting(OTHER_ORG.toString())
            listOf(none, count())
        }
        assertEquals(listOf(0, 1), seen, "without the organization nothing, from org B its own row only")
        val write = try {
            server.driver.transaction {
                execute("SET LOCAL ROLE ostrom_rls_probe").getOrThrow()
                setting(OTHER_ORG.toString())
                execute(
                    Statement.create(
                        "INSERT INTO notifications_push_devices (org_id, id, person_id, push_token, provider, " +
                            "platform, created_at) VALUES (:org, :id, 'x', 'ExponentPushToken[forged00]', 'expo', " +
                            "'ios', now())",
                    ).bind("org", ORG).bind("id", Uuid.random()),
                ).getOrThrow()
            }
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (expected: Exception) {
            Result.failure(expected)
        }
        assertTrue(write.isFailure, "a row of another organization cannot be written")
    }

    private suspend fun QueryExecutor.setting(orgId: String) {
        fetchAll(Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)").bind("org", orgId)).getOrThrow()
    }

    private suspend fun QueryExecutor.count(): Int = fetchAll("SELECT COUNT(*) AS n FROM notifications_push_devices")
        .getOrThrow().first().get("n").asString().toInt()
}
