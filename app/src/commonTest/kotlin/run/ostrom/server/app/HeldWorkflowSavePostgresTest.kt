// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.app

import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.StartWebSessionUseCase
import run.ostrom.server.identity.domain.StartedSession
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.kernel.auth.CSRF_COOKIE
import run.ostrom.server.kernel.auth.CSRF_HEADER
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.SESSION_COOKIE
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.Org
import run.ostrom.server.orgs.domain.OrgsRepository
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
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
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * A workflow saved by the CLI's token with the typed model's defaults (`inputs`, `sources`, and on each input
 * `required` and `maxLength`), held for the console and confirmed there: the save is made, never a 500.
 */
class HeldWorkflowSavePostgresTest {
    /** A person who owns a new organization: the organization, their console session and the CLI's admin token. */
    private class Owner(val orgId: Uuid, val session: StartedSession, val cliToken: String)

    private suspend fun TestServer.owner(login: String): Owner {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        val now = Clock.System.now()
        val org = Org(Uuid.random(), "$login-team", "$login team", null, null, now)
        koin.get<OrgsRepository>().create(org, Member(Uuid.random(), org.id, user.id, OrgRole.OWNER, now)) { false }
        val session = koin.get<StartWebSessionUseCase>()(user.id)
        return Owner(org.id, session, deviceToken(session))
    }

    // A browser write: the session cookie, the CSRF header repeating its cookie, from the console's origin.
    private fun HttpRequestBuilder.session(started: StartedSession) {
        header(HttpHeaders.Cookie, "$SESSION_COOKIE=${started.secret}; $CSRF_COOKIE=${started.csrfToken}")
        header(CSRF_HEADER, started.csrfToken)
        header(HttpHeaders.Origin, CONSOLE_ORIGIN)
    }

    private fun HttpRequestBuilder.json(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    // The device flow as the CLI and the console run it: the token is the CLI's own, bound to no organization.
    private suspend fun TestServer.deviceToken(started: StartedSession): String {
        // `ostrom login --admin`: the governance scopes, whose writes a confirmation holds.
        val codes = client.post("/api/v1/auth/device") {
            json("""{"clientName": "laptop", "scopes": ["policy:write", "harness:write", "gates:policy"]}""")
        }.json()
        val answered = client.post("/api/v1/auth/device/answer") {
            session(started)
            json("""{"userCode": "${codes.getValue("userCode").jsonPrimitive.content}", "approve": true}""")
        }
        assertEquals(HttpStatusCode.NoContent, answered.status, answered.bodyAsText())
        val polled = client.post("/api/v1/auth/device/token") {
            json("""{"deviceCode": "${codes.getValue("deviceCode").jsonPrimitive.content}"}""")
        }
        assertEquals(HttpStatusCode.OK, polled.status, polled.bodyAsText())
        return polled.json().getValue("token").jsonPrimitive.content
    }

    // The workflow as the CLI's typed client writes it back: every default and null explicit.
    private fun workflow(name: String, status: String, input: String, version: String = "null") =
        """
        {"id": "deploy", "name": "$name", "domain": "deploy", "description": null, "version": $version, "repos": null,
         "nodes": [
           {"id": "push", "kind": "trigger", "label": "git push on main"},
           {"id": "yes", "kind": "gate", "label": "A second person says yes"}],
         "edges": [{"from": "push", "to": "yes", "label": null, "on": null}, {"from": "yes", "to": null}],
         "start": "push", "inputs": $input, "sessionMode": null, "limits": null, "sources": [],
         "status": "$status", "foundAt": "2026-10-30T08:00:00.000Z", "updatedAt": null, "updatedBy": null}
    """.trimIndent()

    private suspend fun TestServer.heldSaveIsConfirmed(login: String, input: String) {
        val ada = owner(login)
        val created = client.post("/api/v1/workflows") {
            session(ada.session)
            json(MINIMAL)
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val held = client.put("/api/v1/workflows/deploy") {
            bearerAuth(ada.cliToken)
            json("""{"workflow": ${workflow("Fixer", "accepted", input, "1")}, "expectedVersion": 1}""")
        }
        assertEquals(HttpStatusCode.Accepted, held.status, held.bodyAsText())
        val id = held.json().getValue("confirmationId").jsonPrimitive.content
        val answered = client.post("/api/v1/confirmations/$id/answer") {
            session(ada.session)
            json("""{"answer": "confirm"}""")
        }
        assertEquals(HttpStatusCode.OK, answered.status, answered.bodyAsText())
        val read = client.get("/api/v1/confirmations/$id") { bearerAuth(ada.cliToken) }
        assertEquals(HttpStatusCode.OK, read.status, read.bodyAsText())
        assertEquals("confirmed", read.json().getValue("status").jsonPrimitive.content)
        val saved = client.get("/api/v1/workflows/deploy") { session(ada.session) }.json()
        assertEquals("Fixer", saved.getValue("name").jsonPrimitive.content)
        assertEquals("accepted", saved.getValue("status").jsonPrimitive.content)
    }

    @Test
    fun aHeldSaveWithTheModelsDefaultsIsConfirmed() = serverTest(serverModules()) { server ->
        server.heldSaveIsConfirmed(
            "ada",
            """[{"id": "branch", "label": "Branch", "required": false, "default": null, "maxLength": 4096}]""",
        )
    }

    @Test
    fun aHeldSaveWithNoInputsIsConfirmed() = serverTest(serverModules()) { server ->
        server.heldSaveIsConfirmed("bob", "[]")
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private companion object {
        /** What `ostrom harness add` posts: the file's document with its status, none of the model's defaults. */
        const val MINIMAL = """
            {"id": "deploy", "name": "Fix a branch", "domain": "deploy", "status": "proposed",
             "foundAt": "2026-10-30T00:00:00Z", "start": "push",
             "nodes": [{"id": "push", "kind": "trigger", "label": "git push on main"},
                       {"id": "yes", "kind": "gate", "label": "A second person says yes"}],
             "edges": [{"from": "push", "to": "yes"}, {"from": "yes", "to": null}]}
        """

        /** The console's origin in tests: the default `OSTROM_CONSOLE_URL`. */
        const val CONSOLE_ORIGIN = "http://localhost:5173"
    }
}
