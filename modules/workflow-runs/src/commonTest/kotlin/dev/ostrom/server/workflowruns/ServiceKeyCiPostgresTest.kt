// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns

import dev.ostrom.core.api.models.CheckRun
import dev.ostrom.core.api.models.PackageEnvelope
import dev.ostrom.server.compiler.CompilerModule
import dev.ostrom.server.compiler.domain.models.StoredPackage
import dev.ostrom.server.compiler.domain.repositories.CompilerRepository
import dev.ostrom.server.gates.GatesModule
import dev.ostrom.server.identity.IdentityModule
import dev.ostrom.server.identity.domain.ApiToken
import dev.ostrom.server.identity.domain.ApiTokensRepository
import dev.ostrom.server.identity.domain.CLI_SCOPES
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.ListAuditEventsUseCase
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.SecretHasher
import dev.ostrom.server.identity.domain.ServiceKeysRepository
import dev.ostrom.server.identity.domain.TokenKind
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.instance.InstanceModule
import dev.ostrom.server.kernel.auth.ORG_HEADER
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.http.OstromJson
import dev.ostrom.server.launches.LaunchesModule
import dev.ostrom.server.launches.presentation.LaunchResponse
import dev.ostrom.server.orgs.OrgsModule
import dev.ostrom.server.orgs.domain.Member
import dev.ostrom.server.orgs.domain.Org
import dev.ostrom.server.orgs.domain.OrgsRepository
import dev.ostrom.server.receipts.ReceiptsModule
import dev.ostrom.server.runners.RunnersModule
import dev.ostrom.server.sessions.SessionsModule
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import dev.ostrom.server.workflowruns.domain.repositories.RunQuery
import dev.ostrom.server.workflowruns.domain.repositories.WorkflowRunsRepository
import dev.ostrom.server.workflowruns.presentation.WorkflowReceiptResponse
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The CI of an organization with a service key, on the real modules and PostgreSQL: the key reports a check to a
 * session of its organization and signs its workflow receipt once it has ended, each call an audit event in the key's
 * name; it reaches nothing of another organization, nothing without `sessions:write`, nothing once revoked, and none
 * of the other routes (tokens, keys, devices, members, the lists).
 */
class ServiceKeyCiPostgresTest {
    private val modules = listOf(
        InstanceModule, IdentityModule, OrgsModule, ReceiptsModule, GatesModule, SessionsModule, CompilerModule,
        RunnersModule, LaunchesModule, WorkflowRunsModule,
    )
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    /** A person's organization with its package, the person's CLI token, and a service key per scope set. */
    private class Team(val org: Org, val cliToken: String, val ci: String, val ciId: Uuid, val rulesOnly: String)

    private suspend fun TestServer.team(login: String): Team {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        val org = Org(Uuid.random(), "$login-team", "$login team", null, null, now)
        koin.get<OrgsRepository>().create(org, Member(Uuid.random(), org.id, user.id, OrgRole.OWNER, now)) { false }
        suspend fun token(kind: TokenKind, scopes: Set<String>): Pair<String, Uuid> {
            val secret = kind.prefix + "_" + Uuid.random().toHexString()
            val id = Uuid.random()
            val token = ApiToken(id, user.id, org.id, "t", kind, scopes, "hint", null, now, now + 1.hours, null, null)
            koin.get<ApiTokensRepository>().create(token, koin.get<SecretHasher>().hash(secret))
            return secret to id
        }
        val envelope = PackageEnvelope(
            "ostrom.package/1", "v1", "acme/api", DIGEST, "sha256:content", "Ed25519", "k", "sig", BODY, true, false,
        )
        koin.get<CompilerRepository>().savePackage(org.id, StoredPackage(envelope, now.toString(), emptyMap()))
        val (ci, ciId) = token(TokenKind.SERVICE, setOf("sessions:write", "rules:read"))
        val rulesOnly = token(TokenKind.SERVICE, setOf("rules:read")).first
        return Team(org, token(TokenKind.CLI, CLI_SCOPES).first, ci, ciId, rulesOnly)
    }

    private suspend fun TestServer.call(
        token: String?,
        path: String,
        body: String? = null,
        org: Uuid? = null,
    ): HttpResponse = if (body == null) {
        client.get(path) {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            org?.let { header(ORG_HEADER, it.toString()) }
        }
    } else {
        client.post(path) {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            org?.let { header(ORG_HEADER, it.toString()) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    /** A launch of [team]'s person and its session `s-1`; the agent token. */
    private suspend fun TestServer.session(team: Team): String {
        val launch = call(team.cliToken, "/api/v1/launches", LAUNCH).body<LaunchResponse>()
        assertEquals(HttpStatusCode.OK, call(launch.agentToken, "/api/v1/sessions", SESSION).status)
        return launch.agentToken
    }

    private suspend fun TestServer.used(team: Team): List<Pair<String, Map<String, String>>> =
        koin.get<ListAuditEventsUseCase>()(team.org.id, null, null, AUDIT_PAGE)
            .filter { it.entry.action == "service_key.used" }
            .map { it.entry.targetId to it.entry.details }

    @Test
    fun theCiReportsACheckAndSignsTheReceiptOfItsOrganizationsSessionAuditedInTheKeysName() =
        serverTest(modules) { server ->
            val ada = server.team("ada")
            val agent = server.session(ada)

            val reported = server.call(ada.ci, "/api/v1/checks", CHECK)
            assertEquals(HttpStatusCode.Created, reported.status, reported.bodyAsText())
            assertEquals("ada" to "s-1", reported.body<CheckRun>().let { it.dev to it.sessionId }, "The session's")
            val outside = server.call(ada.ci, "/api/v1/checks", CHECK.replace(""""sessionId":"s-1",""", ""))
            assertEquals(HttpStatusCode.Forbidden, outside.status, "No person stands behind a report outside a session")

            assertEquals(HttpStatusCode.OK, server.call(agent, "/api/v1/sessions/s-1/end", "{}").status)
            val signed = server.call(ada.ci, "/api/v1/sessions/s-1/workflow-receipt", RECEIPT)
            assertEquals(HttpStatusCode.OK, signed.status, signed.bodyAsText())
            assertTrue(signed.body<WorkflowReceiptResponse>().verification.ok)

            val used = server.used(ada)
            assertTrue(used.all { it.first == ada.ciId.toString() }, "In the key's name: $used")
            assertEquals(
                mapOf("reportCheck" to 2, "signWorkflowReceipt" to 1),
                used.groupingBy { it.second.getValue("operation") }.eachCount(),
                "Each call before it acts, the refused one too",
            )
            assertTrue(
                used.map { it.second.getValue("path") }.toSet() ==
                    setOf("/api/v1/checks", "/api/v1/sessions/s-1/workflow-receipt"),
            )
        }

    @Test
    fun aKeyOfOneOrganizationReachesNothingOfAnother() =
        serverTest(modules) { server ->
            val ada = server.team("ada")
            val bob = server.team("bob")
            val agent = server.session(ada)

            // Bob's CI names Ada's session, with or without Ada's organization in the header: never found.
            for (org in listOf(null, ada.org.id)) {
                val check = server.call(bob.ci, "/api/v1/checks", CHECK, org)
                assertEquals(HttpStatusCode.NotFound, check.status, check.bodyAsText())
            }
            assertEquals(HttpStatusCode.OK, server.call(agent, "/api/v1/sessions/s-1/end", "{}").status)
            val receipt = server.call(bob.ci, "/api/v1/sessions/s-1/workflow-receipt", RECEIPT, ada.org.id)
            assertEquals(HttpStatusCode.NotFound, receipt.status)

            assertTrue(server.used(ada).isEmpty(), "Nothing written in Ada's organization")
            assertEquals(3, server.used(bob).size, "Bob's key audited in Bob's organization")
            val written = server.koin.get<WorkflowRunsRepository>().checks(ada.org.id, RunQuery(sessionId = "s-1"))
            assertTrue(written.isEmpty(), "Ada's session took no run of Bob's key")
        }

    @Test
    fun aKeyWithoutTheScopeIsForbiddenAndARevokedKeyIsUnauthenticated() =
        serverTest(modules) { server ->
            val ada = server.team("ada")
            server.session(ada)

            assertEquals(HttpStatusCode.Forbidden, server.call(ada.rulesOnly, "/api/v1/checks", CHECK).status)
            val signing = server.call(ada.rulesOnly, "/api/v1/sessions/s-1/workflow-receipt", RECEIPT)
            assertEquals(HttpStatusCode.Forbidden, signing.status)
            assertTrue(server.used(ada).isEmpty(), "Refused before anything is recorded")

            assertTrue(server.koin.get<ServiceKeysRepository>().revoke(ada.org.id, ada.ciId, now))
            assertEquals(HttpStatusCode.Unauthorized, server.call(ada.ci, "/api/v1/checks", CHECK).status)
            val revoked = server.call(ada.ci, "/api/v1/sessions/s-1/workflow-receipt", RECEIPT)
            assertEquals(HttpStatusCode.Unauthorized, revoked.status)
            assertEquals(HttpStatusCode.Unauthorized, server.call(null, "/api/v1/checks", CHECK).status)
        }

    @Test
    fun aKeyNeverReachesTheTokensTheKeysTheDevicesTheMembersNorTheLists() =
        serverTest(modules) { server ->
            val ada = server.team("ada")
            server.session(ada)
            val org = ada.org.id
            val reads = listOf(
                "/api/v1/tokens", "/api/v1/service-keys", "/api/v1/devices", "/api/v1/orgs/$org/members",
                "/api/v1/checks?sessionId=s-1", "/api/v1/notifications?sessionId=s-1", "/api/v1/me/organizations",
            )
            val writes = listOf(
                "/api/v1/tokens" to """{"name":"x","scopes":["profile:read"]}""",
                "/api/v1/service-keys" to """{"name":"x","scopes":["rules:read"]}""",
                "/api/v1/notifications" to NOTIFY,
            )
            val answers = reads.map { it to server.call(ada.ci, it, org = org).status } +
                writes.map { (path, body) -> path to server.call(ada.ci, path, body, org).status } +
                ("DELETE /api/v1/orgs/$org/members/ada" to server.client.delete("/api/v1/orgs/$org/members/ada") {
                    header(HttpHeaders.Authorization, "Bearer ${ada.ci}")
                }.status)
            val refusals = setOf(HttpStatusCode.Forbidden, HttpStatusCode.NotFound, HttpStatusCode.Unauthorized)
            assertTrue(answers.all { it.second in refusals }, answers.toString())
            assertTrue(server.used(ada).isEmpty(), "None of them admits a key")
        }

    private companion object {
        const val AUDIT_PAGE = 50
        const val DIGEST = "sha256:runs"
        const val LAUNCH = """{"repo":"acme/api","agent":"claude-code"}"""
        const val SESSION = """{"id":"s-1","agent":"claude-code","repo":"acme/api"}"""
        const val CHECK = """{"sessionId":"s-1","digest":"sha256:runs","nodeId":"n-check","scriptId":"sc-1",""" +
            """"scriptSha":"sha-1","exit":0,"outcome":"passed","sandbox":"ci"}"""
        const val NOTIFY =
            """{"sessionId":"s-1","digest":"sha256:runs","nodeId":"n-notify","notifyId":"nt-1","command":"git push"}"""
        const val RECEIPT = """{"digest":"sha256:runs","agent":"claude-code"}"""
        val BODY: String = OstromJson.encodeToString(
            JsonObject.serializer(),
            OstromJson.parseToJsonElement(
                """{"nodes":[
                {"id":"n-check","workflowId":"wf1","workflowName":"Ship","kind":"check","label":"Tests"},
                {"id":"n-notify","workflowId":"wf1","workflowName":"Ship","kind":"notify","label":"Tell"}],
                "scripts":[{"id":"sc-1","nodeId":"n-check","sha256":"sha-1","mode":"enforce"}],
                "notifications":[{"id":"nt-1","nodeId":"n-notify","channel":"log:team",
                "message":"{dev} pushed {command} in {repo}","mode":"enforce"}]}""",
            ).jsonObject,
        )
    }
}
