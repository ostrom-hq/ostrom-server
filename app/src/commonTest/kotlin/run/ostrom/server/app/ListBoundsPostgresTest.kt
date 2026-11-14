// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.app

import run.ostrom.server.app.isolation.HOOK_BODY
import run.ostrom.server.app.isolation.MatrixBucket
import run.ostrom.server.app.isolation.activateArtifacts
import run.ostrom.server.artifacts.domain.storage.ArtifactObjectStore
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.StartWebSessionUseCase
import run.ostrom.server.identity.domain.StartedSession
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.kernel.auth.CSRF_COOKIE
import run.ostrom.server.kernel.auth.CSRF_HEADER
import run.ostrom.server.kernel.auth.ORG_HEADER
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.SESSION_COOKIE
import run.ostrom.server.kernel.config.EnvironmentConfigSource
import run.ostrom.server.kernel.http.ReadLimits
import run.ostrom.server.kernel.http.ReadThrottle
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.Org
import run.ostrom.server.orgs.domain.OrgsRepository
import run.ostrom.server.testing.Contract
import run.ostrom.server.testing.SentStatement
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.request.HttpRequestBuilder
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
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Every list operation of ostrom-core's contract, enumerated from `api/openapi.yaml` so none is forgotten, against the
 * three rules of a list (SRV-LECTURES): `limit=100000` is refused (400) or brought within the declared maximum, and
 * never reaches the database whole; `limit=0` and a negative limit are refused (400); a cursor that no page of this
 * organization gave (garbage, forged, or another organization's) is refused (400), never a 500 nor another
 * organization's rows. A list that declares no `limit` or no cursor ignores the parameter, without a 500.
 *
 * Organization A (Ada) holds a little of everything a minimal bootstrap can make; organization B (Bob) holds nothing,
 * and replays A's cursors. Runs only where CI gives the contract (`OSTROM_CONTRACT_OPENAPI`).
 */
class ListBoundsPostgresTest {
    private class Person(val orgId: Uuid, val memberId: Uuid, val session: StartedSession)

    /** What the walk found: one line per broken rule, the lists it could not reach, the cursors it could not get. */
    private class Findings {
        val broken = mutableListOf<String>()
        val unreached = mutableMapOf<String, Int>()
        val withoutForeignCursor = mutableSetOf<String>()
    }

    // Many reads from one person: the read throttle of the costly routes is not under test here.
    private val unthrottled = module {
        single { ReadThrottle(Clock.System, ReadLimits(MANY, MANY, MANY), ReadThrottle.MAX_SUBJECTS) }
        // Artifacts are on with a bucket only: an in-memory one.
        single<ArtifactObjectStore> { MatrixBucket() }
    }

    @Test
    fun everyListOfTheContractBoundsItsPageAndRefusesACursorItDidNotGive() =
        serverTest(serverModules(), unthrottled) { server ->
            val contract = Contract.from(EnvironmentConfigSource()) ?: return@serverTest
            with(server) {
                val ada = person("ada")
                val bob = person("bob")
                val ids = seed(ada)
                val findings = Findings()
                ListOperations.of(contract).forEach { list -> walk(list, ada, bob, ids, findings) }
                val report = buildList {
                    if (findings.unreached != UNREACHED) {
                        add("Unreached lists (status): ${findings.unreached.toList().sortedBy { it.first }}")
                    }
                    if (findings.withoutForeignCursor != WITHOUT_FOREIGN_CURSOR) {
                        add("No cursor of A to replay in B: ${findings.withoutForeignCursor.sorted()}")
                    }
                    addAll(findings.broken)
                }
                assertTrue(report.isEmpty(), report.joinToString("\n"))
            }
        }

    private suspend fun TestServer.walk(
        list: ListOperation,
        ada: Person,
        bob: Person,
        ids: Map<String, String>,
        f: Findings,
    ) {
        val path = path(list.operation.path, ada.orgId, ids)
        val plain = read(ada, path, "")
        if (plain.status != HttpStatusCode.OK) {
            f.unreached[list.operation.id] = plain.status.value
            return
        }
        limits(list, ada, path, f)
        cursors(list, ada, bob, path, f)
    }

    // The three page sizes, on a list that declares `limit`; ignored without a 500 on one that does not.
    private suspend fun TestServer.limits(list: ListOperation, ada: Person, path: String, f: Findings) {
        val id = list.operation.id
        listOf("0", "-1").forEach { size ->
            val status = read(ada, path, "limit=$size").status
            if (list.paged && status != HttpStatusCode.BadRequest) f.broken += "$id limit=$size: $status, not 400"
            if (status.value >= SERVER_ERROR) f.broken += "$id limit=$size: $status"
        }
        var huge: HttpResponse? = null
        val sent = driver.record { huge = read(ada, path, "limit=$HUGE") }
        val answer = checkNotNull(huge)
        if (answer.status.value >= SERVER_ERROR) f.broken += "$id limit=$HUGE: ${answer.status}"
        if (sent.any { asksHuge(it) }) f.broken += "$id limit=$HUGE: the database was asked for $HUGE rows"
        val count = if (answer.status == HttpStatusCode.OK) items(answer)?.size else null
        val maximum = list.maximum.takeIf { list.paged }
        if (count != null && maximum != null && count > maximum) {
            f.broken += "$id limit=$HUGE: $count items, above the maximum $maximum"
        }
    }

    // The size asked, or one more (the row that says another page follows); not a sentinel such as the audit's
    // `Long.MAX_VALUE` for "no cursor".
    private fun asksHuge(statement: SentStatement): Boolean =
        HUGE.toString() in statement.sql || statement.statement?.namedParametersValues.orEmpty().values.any {
            (it as? Number)?.toLong()?.let { value -> value == HUGE.toLong() || value == HUGE + 1L } == true
        }

    // Garbage and forged cursors from A, then A's own cursor replayed by B.
    private suspend fun TestServer.cursors(list: ListOperation, ada: Person, bob: Person, path: String, f: Findings) {
        val id = list.operation.id
        ListOperations.CURSORS.forEach { name ->
            listOf(GARBAGE, "${Uuid.random()}").forEach { cursor ->
                val status = read(ada, path, "$name=${cursor.encodeURLParameter()}").status
                val declared = name in list.cursors
                if (declared && status != HttpStatusCode.BadRequest) f.broken += "$id $name=$cursor: $status, not 400"
                if (status.value >= SERVER_ERROR) f.broken += "$id $name=$cursor: $status"
            }
        }
        if (list.cursors.isEmpty()) return
        val page = read(ada, path, if (list.paged) "limit=1" else "")
        val body = Json.parseToJsonElement(page.bodyAsText())
        val seen = items(body)?.mapNotNull { it.id() }.orEmpty()
        val cursor = (body as? JsonObject)?.get("next")?.text() ?: seen.firstOrNull()
        if (cursor == null) {
            f.withoutForeignCursor += id
            return
        }
        // Under one of A's resources (an automation's runs), B is told it does not exist, as for any id.
        val refused = if (RESOURCE.containsMatchIn(list.operation.path)) {
            setOf(HttpStatusCode.BadRequest, HttpStatusCode.NotFound)
        } else {
            setOf(HttpStatusCode.BadRequest)
        }
        val bobs = path(list.operation.path, bob.orgId, emptyMap())
        list.cursors.forEach { name ->
            val replayed = read(bob, bobs, "$name=${cursor.encodeURLParameter()}")
            val text = replayed.bodyAsText()
            if (replayed.status !in refused) f.broken += "$id $name=<A's>: ${replayed.status}, not 400"
            if (seen.any { it in text }) f.broken += "$id $name=<A's>: B was answered A's rows"
        }
    }

    private suspend fun TestServer.person(login: String): Person {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        val now = Clock.System.now()
        val org = Org(Uuid.random(), "$login-lists", "$login lists", null, null, now)
        val member = Member(Uuid.random(), org.id, user.id, OrgRole.OWNER, now)
        koin.get<OrgsRepository>().create(org, member) { false }
        check(activateArtifacts(org.id, login)) { "The artifacts of $login are not on." }
        return Person(org.id, member.id, koin.get<StartWebSessionUseCase>()(user.id))
    }

    // Two rules and two artifacts (a page of one has a next), an automation and an MCP server (their sub-lists), an
    // accepted workflow and one run of it (the runs, listed by the organization and by the workflow): the ids of the
    // created resources by their collection, for the paths that name one.
    private suspend fun TestServer.seed(ada: Person): Map<String, String> {
        repeat(2) { n ->
            write(ada, "/api/v1/rules", """{"statement": "Rule $n of the lists", "group": "Lists"}""")
            write(ada, "/api/v1/artifacts", """{"title": "Page $n", "files": [{"path": "a.html", "content": "$n"}]}""")
        }
        val automation = write(
            ada,
            "/api/v1/automations",
            """{"name": "Lists", "trigger": {"kind": "webhook"}, "action": {"prompt": "List"}}""",
        )
        write(
            ada,
            "/api/v1/mcp/servers",
            """{"name": "lists", "transport": "http", "url": "https://mcp.example.com/mcp", "enabled": true,
                "scope": "shared"}""",
        )
        val workflow = write(
            ada,
            "/api/v1/workflows",
            """{"id": "lists", "name": "Lists", "domain": "git", "nodes": [{"id": "t", "kind": "trigger",
                "label": "Push"}], "edges": [], "start": "t", "status": "accepted",
                "foundAt": "2026-11-03T10:00:00Z", "inputs": []}""",
        )
        workflow?.let { write(ada, "/api/v1/workflows/$it/runs", "{}") }
        val artifact = read(ada, "/api/v1/artifacts", "").let { items(it)?.firstOrNull()?.id() }
        // A team skill, a personal skill and a team hook, each with one version (their versions and assignments).
        write(ada, "/api/v1/skills/lists/versions", SKILL_VERSION)
        write(ada, "/api/v1/me/skills/mine/versions", SKILL_VERSION)
        write(ada, "/api/v1/hooks/lists/versions", HOOK_BODY)
        return listOfNotNull(
            automation?.let { "/api/v1/automations" to it },
            workflow?.let { "/api/v1/workflows" to it },
            artifact?.let { "/api/v1/artifacts" to it },
            "/api/v1/mcp/servers" to "lists",
            "/api/v1/skills" to "lists",
            "/api/v1/me/skills" to "mine",
            "/api/v1/hooks" to "lists",
            "/api/v1/members" to "${ada.memberId}",
        ).toMap()
    }

    // The id of what a write created, or null when it was refused (the lists under it are then unreached).
    private suspend fun TestServer.write(person: Person, path: String, body: String): String? {
        val answer = client.post(path) {
            session(person)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return if (answer.status.value in SUCCESS) Json.parseToJsonElement(answer.bodyAsText()).id() else null
    }

    private suspend fun TestServer.read(person: Person, path: String, query: String): HttpResponse =
        client.get(if (query.isEmpty()) path else "$path?$query") { session(person) }

    private fun HttpRequestBuilder.session(person: Person) {
        val session = person.session
        header(HttpHeaders.Cookie, "$SESSION_COOKIE=${session.secret}; $CSRF_COOKIE=${session.csrfToken}")
        header(CSRF_HEADER, session.csrfToken)
        header(HttpHeaders.Origin, CONSOLE_ORIGIN)
        header(ORG_HEADER, "${person.orgId}")
    }

    private suspend fun items(answer: HttpResponse) = ListOperations.items(Json.parseToJsonElement(answer.bodyAsText()))

    private fun items(body: JsonElement) = ListOperations.items(body)

    private fun JsonElement.id(): String? = (this as? JsonObject)?.get("id")?.text()

    private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.contentOrNull

    // The organization's id for `{org}`, a created resource's id for its collection, else an id that names nothing.
    private fun path(template: String, orgId: Uuid, ids: Map<String, String>): String =
        PARAMETER.replace(template) { match ->
            when (match.groupValues[1]) {
                "org", "orgId" -> "$orgId"
                else -> ids[template.substringBefore("/{")] ?: "${Uuid.random()}"
            }
        }

    private companion object {
        const val CONSOLE_ORIGIN = "http://localhost:5173"
        const val HUGE = 100_000
        const val MANY = 1_000
        const val SERVER_ERROR = 500
        const val GARBAGE = "not a cursor%~/"

        // A skill version: its one file, SKILL.md, base64 of "# Skill".
        const val SKILL_VERSION = """{"version": "1.0.0",""" +
            """ "files": [{"path": "SKILL.md", "contentBase64": "IyBTa2lsbA=="}]}"""
        val SUCCESS = 200..299
        val PARAMETER = Regex("\\{(\\w+)}")
        val RESOURCE = Regex("\\{(?!org\\b|orgId\\b)\\w+}")

        /** The lists a minimal bootstrap cannot reach, with the status they answer. */
        val UNREACHED = mapOf(
            // The instance's administration, served since ostrom-core#186 and #199: an instance administrator's, whom a
            // minimal bootstrap does not make.
            "listCloudClusters" to 403,
            "listInstanceAdmins" to 403,
            "listInstanceSettings" to 403,
        )

        /**
         * The paged lists whose page of A is empty after the minimal bootstrap: nothing of A to replay in B. Their
         * cursor names a row by its id (refused when B cannot see it, as the forged id above is) or is a position in
         * time the contract accepts as such (MCP calls, usage events: `before` an instant), which row-level
         * security keeps to B's own rows.
         */
        val WITHOUT_FOREIGN_CURSOR = setOf(
            "listApprovals",
            "listAutomationRuns",
            "listChecks",
            "listCloudSessions",
            "listMcpCalls",
            "listNotifications",
            "listRepos",
            "listSessions",
            "listTeams",
            "listUsageEvents",
            "listWaits",
        )
    }
}
