// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.todos

import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.core.api.models.SessionTodo
import run.ostrom.core.api.models.StartTodoItemResponse
import run.ostrom.core.api.models.TodoStopDecision
import run.ostrom.core.api.models.TodoStopHook
import run.ostrom.core.api.models.TodoStopHooks
import run.ostrom.server.automations.domain.WaitAgent
import run.ostrom.server.automations.domain.WaitAgentResolver
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.ALL_USER_SCOPES
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.testing.serverTest
import run.ostrom.server.todos.domain.TodoArtifacts
import run.ostrom.server.todos.domain.TodoCredentialRefusedException
import run.ostrom.server.todos.domain.TodoListStatus
import run.ostrom.server.todos.domain.TodoSessionNotFoundException
import run.ostrom.server.todos.domain.TodoSessions
import run.ostrom.server.todos.domain.TodoWaits
import run.ostrom.server.todos.domain.TodosRepository
import run.ostrom.server.todos.fakes.ADA
import run.ostrom.server.todos.fakes.BEN
import run.ostrom.server.todos.fakes.FakeArtifacts
import run.ostrom.server.todos.fakes.FakeMembers
import run.ostrom.server.todos.fakes.FakeSessions
import run.ostrom.server.todos.fakes.FakeWaits
import run.ostrom.server.todos.fakes.LONG
import run.ostrom.server.todos.fakes.ORG_A
import run.ostrom.server.todos.fakes.ROOT
import run.ostrom.server.todos.presentation.TodoCallers
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The todo routes on a real PostgreSQL: the session's agent token and its person change the todo, an administrator
 * reads it, an administrator who signed in turns a member's Stop hook off; anyone else is refused.
 */
class TodosRoutesPostgresTest {
    private val overrides = module {
        single<TokenAuthenticator> { Tokens }
        single<PrincipalOrgResolver> { Orgs }
        single<WaitAgentResolver> { Agents }
        single<MemberDirectory> { FakeMembers() }
        single<TodoSessions> { FakeSessions() }
        single<TodoWaits> { FakeWaits() }
        single<TodoArtifacts> { FakeArtifacts() }
    }

    private fun todoTest(block: suspend HttpClient.() -> Unit) =
        serverTest(listOf(TodosModule), overrides) { server -> server.client.block() }

    private suspend fun HttpClient.send(token: String?, method: String, path: String, body: String = "{}") =
        when (method) {
            "GET" -> get(path) { token?.let { bearerAuth(it) } }
            "PUT" -> put(path) { json(token, body) }
            else -> post(path) { json(token, body) }
        }

    private fun HttpRequestBuilder.json(token: String?, body: String) {
        token?.let { bearerAuth(it) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun theAgentAndItsPersonKeepTheTodoAnAdministratorReadsIt() = todoTest {
        assertTrue(send("ada", "GET", "$SESSION?sessionId=s1").bodyAsText().contains("\"todo\":null"))
        assertTrue(send("ada", "GET", "$SESSION/context?sessionId=s1").bodyAsText().contains("\"text\":null"))
        val created = send("agent", "POST", SESSION, """{"sessionId": "s1", "reference": "The plan", "items": [$A]}""")
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals(listOf("a"), created.body<SessionTodo>().todo!!.items.map { it.id })
        val again = send("ada", "POST", SESSION, """{"sessionId": "s1", "reference": "R", "items": [$A]}""")
        assertEquals(HttpStatusCode.Conflict, again.status, "one todo per session")

        val todo: suspend (String) -> HttpResponse = { token -> send(token, "GET", "$SESSION?sessionId=s1") }
        assertEquals("The plan", todo("ada").body<SessionTodo>().todo!!.reference)
        assertEquals("The plan", todo("root").body<SessionTodo>().todo!!.reference, "an administrator reads")
        assertEquals(HttpStatusCode.NotFound, todo("ben").status, "another person's session")
        assertEquals(HttpStatusCode.Forbidden, todo("service").status)
        assertEquals(HttpStatusCode.Forbidden, todo("device").status)
        assertEquals(HttpStatusCode.NotFound, todo("nobody").status, "no organization")
        assertEquals(HttpStatusCode.BadRequest, send("agent", "GET", SESSION).status, "an agent names its session")
        assertEquals(HttpStatusCode.BadRequest, send("ada", "GET", SESSION).status, "so does a person")
        assertEquals(HttpStatusCode.NotFound, send("agent", "GET", "$SESSION?sessionId=s9").status, "not its own")
        assertEquals(HttpStatusCode.Forbidden, send("ended", "GET", "$SESSION?sessionId=s1").status, "a launch over")
        val context = send("agent", "GET", "$SESSION/context?sessionId=s1").bodyAsText()
        assertTrue(context.contains("[Ostrom todo]"), context)

        val added = send("adacli", "POST", "$SESSION/items", """{"sessionId": "s1", "item": $C, "reason": "Found"}""")
        assertEquals(listOf("a", "c"), added.body<SessionTodo>().todo!!.items.map { it.id })
        val started = send("agent", "POST", "$SESSION/items/a/start", """{"sessionId": "s1"}""")
        assertTrue(started.body<StartTodoItemResponse>().marker.startsWith("<!-- ostrom-todo: "))
        val noted = send("ada", "POST", "$SESSION/items/a/notes", """{"sessionId": "s1", "text": "Halfway"}""")
        assertEquals("Halfway", noted.body<SessionTodo>().todo!!.items.first().notes.single().text)
        val pull = "https://github.com/acme/x/pull/1"
        val link = """{"sessionId": "s1", "kind": "pr", "ref": "$pull", "number": 1}"""
        val linked = send("agent", "POST", "$SESSION/items/a/link", link)
        assertEquals(pull, linked.body<SessionTodo>().todo!!.items.first().links.single().ref)
        val zzz = """{"sessionId": "s1", "kind": "zzz", "ref": "x"}"""
        val unknownKind = send("agent", "POST", "$SESSION/items/a/link", zzz)
        assertEquals(HttpStatusCode.BadRequest, unknownKind.status)
        val proof = """{"sessionId": "s1", "checks": [{"index": 0, "passed": true, "observed": "merged"}]}"""
        val done = send("agent", "POST", "$SESSION/items/a/proof", proof)
        assertEquals("done", done.body<SessionTodo>().todo!!.items.first().status.value)
        val dropped = send("agent", "POST", "$SESSION/items/c/drop", """{"sessionId": "s1", "reason": "Not needed"}""")
        assertEquals("drop_requested", dropped.body<SessionTodo>().todo!!.items.last().status.value)
        val failed = send("agent", "POST", "$SESSION/hook-error", """{"sessionId": "s1", "text": "gh failed"}""")
        assertEquals("hook_error", failed.body<SessionTodo>().todo!!.hookEvents.single().kind.value)
        val stopped = send("agent", "POST", "$SESSION/stop", """{"sessionId": "s1", "phase": "begin"}""")
        assertEquals(HttpStatusCode.OK, stopped.status)
        stopped.body<TodoStopDecision>()
        val phase = send("agent", "POST", "$SESSION/stop", """{"sessionId": "s1", "phase": "later"}""")
        assertEquals(HttpStatusCode.BadRequest, phase.status)

        val note = """{"sessionId": "s1", "text": "Mine"}"""
        val notes = "$SESSION/items/a/notes"
        assertEquals(HttpStatusCode.Forbidden, send("root", "POST", notes, note).status, "an administrator reads only")
        assertEquals(HttpStatusCode.NotFound, send("ben", "POST", notes, note).status)
        assertEquals(HttpStatusCode.Forbidden, send("service", "POST", notes, note).status)
        assertEquals(HttpStatusCode.Forbidden, send("adaapp", "POST", notes, note).status)
    }

    @Test
    fun aClosedTodoIsResetByItsAgentAndWrittenToTheAuditLog() = serverTest(listOf(TodosModule), audited) { server ->
        val client = server.client
        val reset = """{"sessionId": "s1"}"""
        assertEquals(HttpStatusCode.NotFound, client.send("agent", "POST", "$SESSION/reset", reset).status)
        client.send("agent", "POST", SESSION, """{"sessionId": "s1", "reference": "The plan", "items": [$A]}""")
        assertEquals(HttpStatusCode.Conflict, client.send("agent", "POST", "$SESSION/reset", reset).status, "active")
        val todos = server.koin.get<TodosRepository>()
        todos.mutate(ORG_A, "s1") { it.copy(status = TodoListStatus.DONE).let { done -> done to done } }
        assertEquals(HttpStatusCode.Forbidden, client.send("root", "POST", "$SESSION/reset", reset).status)
        val cleared = client.send("agent", "POST", "$SESSION/reset", reset)
        assertEquals(HttpStatusCode.OK to null, cleared.status to cleared.body<SessionTodo>().todo)
        val record = records.single()
        assertEquals(listOf("todo.reset", "session", "s1"), listOf(record.event, record.subjectType, record.subjectId))
        assertEquals(mapOf("status" to "done", "items" to "1"), record.details - "list")
        assertEquals(null, client.send("ada", "GET", "$SESSION?sessionId=s1").body<SessionTodo>().todo, "cleared")
    }

    private val records = mutableListOf<OrgAuditRecord>()

    private val audited = module {
        includes(overrides)
        single<OrgAuditTrail> { OrgAuditTrail { records += it } }
    }

    @Test
    fun onlyAnAdministratorWhoSignedInTurnsAMembersStopHookOff() = todoTest {
        assertEquals(TodoStopHooks(emptyList()), send("root", "GET", HOOK).body<TodoStopHooks>())
        val off = send("root", "PUT", "$HOOK/members/$BEN", """{"enabled": false}""")
        assertEquals(TodoStopHook(BEN, enabled = false), off.body<TodoStopHook>())
        assertEquals(TodoStopHooks(listOf(TodoStopHook(BEN, false))), send("root", "GET", HOOK).body<TodoStopHooks>())
        val on = """{"enabled": true}"""
        assertEquals(HttpStatusCode.Forbidden, send("rootcli", "PUT", "$HOOK/members/$BEN", on).status, "a token")
        assertEquals(HttpStatusCode.Forbidden, send("ada", "PUT", "$HOOK/members/$BEN", on).status, "a member")
        assertEquals(HttpStatusCode.Forbidden, send("agent", "GET", HOOK).status)
        assertEquals(HttpStatusCode.Forbidden, send("rootcli", "GET", HOOK).status)
        val stranger = Uuid.random().toString()
        assertEquals(HttpStatusCode.NotFound, send("root", "PUT", "$HOOK/members/$stranger", on).status)
    }

    @Test
    fun everyRouteNeedsACredential() = todoTest {
        val routes = listOf(
            "GET" to SESSION, "POST" to SESSION, "POST" to "$SESSION/items", "POST" to "$SESSION/items/a/start",
            "POST" to "$SESSION/items/a/notes", "POST" to "$SESSION/items/a/proof", "POST" to "$SESSION/items/a/link",
            "POST" to "$SESSION/items/a/drop", "GET" to "$SESSION/context", "POST" to "$SESSION/stop",
            "POST" to "$SESSION/hook-error", "POST" to "$SESSION/reset", "GET" to HOOK, "PUT" to "$HOOK/members/$BEN",
        )
        for ((method, path) in routes) {
            assertEquals(HttpStatusCode.Unauthorized, send(null, method, path).status, "$method $path")
        }
    }

    @Test
    fun aMachineThatGetsPastTheGatesIsStillRefused() = serverTest(listOf(TodosModule), overrides) { server ->
        // The kernel stops a service key or a device before these routes; the callers refuse them all the same.
        val callers = server.koin.get<TodoCallers>()
        for (kind in listOf(PrincipalKind.SERVICE, PrincipalKind.DEVICE)) {
            val machine = AuthenticatedPrincipal("m", kind, setOf(ALL_USER_SCOPES))
            assertFailsWith<TodoCredentialRefusedException> { callers.reader(machine, "s1") }
        }
    }

    @Test
    fun theModuleServesTodosV1() = serverTest(listOf(TodosModule), overrides) { server ->
        assertEquals(setOf(ServerFeatures.TODOS), TodosModule.features(server.koin))
    }

    private object Tokens : TokenAuthenticator {
        override suspend fun authenticate(token: String): AuthenticatedPrincipal? = when (token) {
            "ada", "ben", "root", "nobody" -> person(token, PrincipalChannel.BROWSER)
            "adacli" -> person("ada", PrincipalChannel.API_TOKEN)
            "adaapp" -> person("ada", PrincipalChannel.MOBILE_APP)
            "rootcli" -> person("root", PrincipalChannel.API_TOKEN)
            "agent" -> AuthenticatedPrincipal(LAUNCH, PrincipalKind.AGENT, setOf("agent:session"))
            "ended" -> AuthenticatedPrincipal("lch_0", PrincipalKind.AGENT, setOf("agent:session"))
            "service" -> AuthenticatedPrincipal("svc", PrincipalKind.SERVICE, emptySet())
            "device" -> AuthenticatedPrincipal("dev", PrincipalKind.DEVICE, emptySet())
            else -> null
        }

        private fun person(login: String, channel: PrincipalChannel) =
            AuthenticatedPrincipal(login, PrincipalKind.USER, setOf(ALL_USER_SCOPES), channel = channel)
    }

    // Ada and Ben are members of org A, Root its administrator; nobody belongs to no organization.
    private object Orgs : PrincipalOrgResolver {
        override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
            when (principal.subjectId) {
                "ada" -> OrgPrincipal(ORG_A, Uuid.parse(ADA), OrgRole.MEMBER)
                "ben" -> OrgPrincipal(ORG_A, Uuid.parse(BEN), OrgRole.MEMBER)
                "root" -> OrgPrincipal(ORG_A, Uuid.parse(ROOT), OrgRole.ADMIN)
                else -> null
            }
    }

    // Ada's launch, whose session is `s1` (its open segment `s1~r2`); another session is not found, as the sessions
    // module's guard answers; a launch that is over is no one.
    private object Agents : WaitAgentResolver {
        override suspend fun resolve(principal: AuthenticatedPrincipal, sessionId: String): WaitAgent? {
            val agent = WaitAgent(ORG_A, ADA, "s1~r2", listOf("s1", "s1~r2")).takeIf { principal.subjectId == LAUNCH }
            if (agent != null && sessionId !in agent.segments) throw TodoSessionNotFoundException()
            return agent
        }
    }

    private companion object {
        const val SESSION = "/api/v1/todos/session"
        const val HOOK = "/api/v1/todos/stop-hook"
        const val LAUNCH = "lch_1"
        val A = item("a")
        val C = item("c")

        fun item(id: String) = """{"id": "$id", "name": "Item $id", "description": "$LONG",""" +
            """ "doneWhen": {"text": "Merged", "checks": [{"type": "pr_merged", "repo": "acme/x"}]}}"""
    }
}
