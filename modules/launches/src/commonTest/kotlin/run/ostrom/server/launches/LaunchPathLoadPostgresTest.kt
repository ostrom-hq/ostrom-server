// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.launches

import run.ostrom.core.api.models.Workflow
import run.ostrom.core.api.models.WorkflowEdge
import run.ostrom.core.api.models.WorkflowNode
import run.ostrom.core.api.models.WorkflowNodeKind
import run.ostrom.core.api.models.WorkflowStatus
import run.ostrom.server.compiler.CompilerModule
import run.ostrom.server.compiler.domain.models.Authorship
import run.ostrom.server.compiler.domain.repositories.CompilerRepository
import run.ostrom.server.compiler.domain.services.CompiledPackages
import run.ostrom.server.compiler.domain.services.PackageCompiler
import run.ostrom.server.identity.domain.ApiToken
import run.ostrom.server.identity.domain.ApiTokensRepository
import run.ostrom.server.identity.domain.CLI_SCOPES
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.SecretHasher
import run.ostrom.server.identity.domain.TokenKind
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.launches.domain.models.Launch
import run.ostrom.server.launches.domain.models.LaunchLimit
import run.ostrom.server.launches.infrastructure.PostgresLaunchesRepository
import run.ostrom.server.launches.presentation.LaunchResponse
import run.ostrom.server.orgs.domain.Invitation
import run.ostrom.server.orgs.domain.InvitationsRepository
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.Org
import run.ostrom.server.orgs.domain.OrgsRepository
import run.ostrom.server.testing.ReadMeasure
import run.ostrom.server.testing.SentStatement
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.assertNoScan
import run.ostrom.server.testing.benchTest
import run.ostrom.server.testing.measureRead
import run.ostrom.server.testing.writeReadReport
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.ktor.client.HttpClient
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
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.jsonObject
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * The launch path of `ostrom claude`, measured on PostgreSQL with the real identity, orgs, launches, sessions, gates
 * and compiler modules (ENG perf pass of the launch, `.coord/PERF-LANCEMENT.md`). The path is what the CLI and the
 * hooks call, in order: the agent policy, the session package and the launch with the CLI's token; then, with the
 * launch's agent token, the session's start, the signed gates catalogue, the first governed tool call (a gate asked),
 * the session's end and the launch's end (R2b), which revokes the agent token.
 *
 * Three measures, written to `build/reports/perf/launch-*.txt`:
 * - each route alone, on tables filled past 20 000 rows by another organization: its database p95 at 20 concurrent
 *   callers, its queries and its plans, none of which scans a filled table;
 * - 100 launches at once in one organization, each person its own: the wall time of each route, every call answered,
 *   no deadlock, and every agent token refused once its launch ended; then the launch's own transaction (its advisory
 *   lock, its two counts and its insert) by 100 callers on a pool of 20, the contention the in-process server hides;
 * - 1 000 launches ended in two organizations: no agent token left active, and the rows each one leaves.
 */
class LaunchPathLoadPostgresTest {
    private val modules = launchModules(CompilerModule)
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    /** A member of an organization, and a CLI token of theirs bound to it. */
    private class Person(val login: String, val userId: Uuid, val cliToken: String)

    /** An organization and its members, the first its owner. */
    private class Team(val org: Org, val people: List<Person>)

    /** A launch, its agent token and the session it opened. */
    private class Started(val launchId: String, val agentToken: String, val sessionId: String)

    @Test
    fun eachRouteOfTheLaunchPathKeepsToItsIndexesOnFilledTables() = benchTest(modules, null) { server ->
        val team = server.team("ada", PEOPLE_FOR_ROUTES)
        server.release(team.org)
        server.fill(team)
        val ada = team.people.first()
        var turn = 0
        fun next(): Person = team.people[turn++ % team.people.size]
        val open = server.launchOf(ada)
        server.startSession(open.agentToken, open.launchId, "s-open")
        val toEnd = List(MEASURED_CALLS) { server.launchOf(next()) }.iterator()
        val sessions = List(MEASURED_CALLS) { "s-end-$it" }
            .onEach { server.startSession(open.agentToken, open.launchId, it) }
            .iterator()
        var started = 0
        var asked = 0
        // The reads: their database time at 20 concurrent callers (ReadBench.kt).
        val reads = listOf(
            server.measureRead("GET /policy/agents") { get(POLICY) { bearer(ada.cliToken) } },
            server.measureRead("GET /packages/current") { get(PACKAGE) { bearer(ada.cliToken) } },
            server.measureRead("GET /gates/catalog/signed") { get(CATALOG) { bearer(open.agentToken) } },
        )
        // The writes, which a replay cannot run again (their keys are taken): their whole call, one at a time, and
        // the plan of every statement under EXPLAIN without running it.
        val writes = listOf(
            server.measureWrite("POST /launches") { post(LAUNCHES) { bearer(next().cliToken); json(LAUNCH) } },
            server.measureWrite("POST /sessions") {
                post(SESSIONS) { bearer(open.agentToken); json(session("s-m-${started++}", open.launchId)) }
            },
            server.measureWrite("POST /gates (governed)") {
                post(GATES) { bearer(open.agentToken); json(ask("s-open", asked++)) }
            },
            server.measureWrite("POST /sessions/{id}/end") {
                post("$SESSIONS/${sessions.next()}/end") { bearer(open.agentToken); json("{}") }
            },
            server.measureWrite("DELETE /launches/{id}") {
                val launch = toEnd.next()
                delete("$LAUNCHES/${launch.launchId}") { bearer(launch.agentToken) }
            },
        )
        writeReadReport("launch-routes-reads", reads)
        write("launch-routes-writes", writes.joinToString("\n") { it.describe() })
        write("launch-routes-summary", summary(reads, writes))
        (reads + writes.map { it.measure }).forEach { it.assertNoScan(FILLED) }
    }

    @Test
    fun aHundredLaunchesAtOnceInOneOrganizationAllAnswerAndEnd() = benchTest(modules, null) { server ->
        val team = server.team("bea", CONCURRENT)
        server.release(team.org)
        val deadlocksBefore = server.deadlocks()
        val packages = server.koin.get<CompiledPackages>()
        val compiledBefore = packages.compilations
        val calls = Calls()
        // Timed alone on the server: no other test process's bench times or loads it meanwhile (exclusively).
        val (finished, wall, contended) = server.database.exclusively {
            val started = TimeSource.Monotonic.markNow()
            // Bounded: a call that waits for a connection it never gets is counted as unfinished, not waited for.
            val ended = withTimeoutOrNull(CONCURRENCY_BUDGET) {
                coroutineScope {
                    team.people.mapIndexed { n, person -> async { server.wholePath(person, n, calls) } }.awaitAll()
                }
            }
            Triple(ended.orEmpty().filterNotNull(), started.elapsedNow(), server.contendedLaunches(team))
        }
        val compilations = packages.compilations - compiledBefore
        val deadlocks = server.deadlocks() - deadlocksBefore
        val active = server.activeAgentTokens(team.org.id)
        val stillActing = finished.count { launch ->
            server.client.post(SESSIONS) { bearer(launch.agentToken); json(session("s-after", launch.launchId)) }
                .status != HttpStatusCode.Unauthorized
        }
        val report = buildString {
            appendLine("$CONCURRENT launches at once, one organization, one person each; in-process server, pool 4")
            appendLine("${finished.size} of $CONCURRENT paths whole in $wall (budget $CONCURRENCY_BUDGET)")
            append(calls.report())
            val transaction = "launch transaction, 100 callers, pool of $CONTENDED_POOL (advisory lock wait included)"
            appendLine(line(transaction, contended))
            appendLine("deadlocks: $deadlocks; agent tokens still active: $active")
            appendLine("ended agent tokens still accepted: $stillActing")
            appendLine("packages compiled for the $CONCURRENT reads of GET /packages/current: $compilations")
        }
        write("launch-concurrency", report)
        assertEquals(CONCURRENT, finished.size, "Every launch goes through its whole path\n$report")
        assertEquals(0L, deadlocks, report)
        assertEquals(0L, active, "Every agent token is revoked once its launch ended (R2b)\n$report")
        assertEquals(0, stillActing, report)
        assertTrue(compilations <= 1, "The same sources are compiled once, not at every launch\n$report")
    }

    @Test
    fun aThousandEndedLaunchesLeaveNoActiveAgentToken() = benchTest(modules, null) { server ->
        val teams = listOf(server.team("cyd", LEAK_PEOPLE), server.team("dov", LEAK_PEOPLE))
        val gate = Semaphore(LEAK_WIDTH)
        coroutineScope {
            teams.flatMap { it.people }.flatMap { person -> List(LAUNCHES_EACH) { person } }
                .mapIndexed { n, person ->
                    async { gate.withPermit { server.shortPath(person, n) } }
                }.awaitAll()
        }
        val orgs = teams.map { it.org.id }
        val active = orgs.sumOf { server.activeAgentTokens(it) }
        val rows = mapOf(
            "launches_launches" to server.count("launches_launches", orgs, ""),
            "identity_api_tokens (AGENT)" to server.count("identity_api_tokens", orgs, " AND kind = 'AGENT'"),
            "sessions_sessions" to server.count("sessions_sessions", orgs, ""),
        )
        val report = "${LAUNCHES_EACH * LEAK_PEOPLE * 2} launches ended in 2 organizations\n" +
            "agent tokens still active: $active\n" +
            rows.entries.joinToString("\n") { (table, n) -> "rows left in $table: $n" }
        write("launch-leaks", report)
        assertEquals(0L, active, report)
    }

    /** Each call of the concurrent paths, by route: its time and its status. */
    private class Calls {
        private val lock = Mutex()
        private val times = mutableMapOf<String, MutableList<Duration>>()
        private val failures = mutableMapOf<String, MutableList<String>>()

        suspend fun <T> timed(route: String, call: suspend () -> HttpResponse, read: suspend (HttpResponse) -> T): T? {
            val start = TimeSource.Monotonic.markNow()
            val answer = call()
            val took = start.elapsedNow()
            val ok = answer.status.isSuccess()
            val failure = if (ok) null else "${answer.status.value} after $took: ${answer.bodyAsText().take(TEXT)}"
            lock.withLock {
                times.getOrPut(route) { mutableListOf() } += took
                failure?.let { failures.getOrPut(route) { mutableListOf() } += it }
            }
            return if (ok) read(answer) else null
        }

        fun report(): String = buildString {
            times.forEach { (route, list) -> appendLine(line(route, list)) }
            failures.forEach { (route, list) ->
                appendLine("$route: ${list.size} failed, first: ${list.first()}")
            }
        }
    }

    // The whole path of one launch, each call timed under its route; null once a call fails.
    private suspend fun TestServer.wholePath(person: Person, n: Int, calls: Calls): Started? {
        val reads = listOf<Pair<String, suspend () -> HttpResponse>>(
            "GET /policy/agents" to { client.get(POLICY) { bearer(person.cliToken) } },
            "GET /packages/current" to { client.get(PACKAGE) { bearer(person.cliToken) } },
        )
        val read = reads.all { (route, call) -> calls.timed(route, call) { } != null }
        val launch = if (read) {
            calls.timed(
                "POST /launches",
                { client.post(LAUNCHES) { bearer(person.cliToken); json(LAUNCH) } },
            ) { it.body<LaunchResponse>() }
        } else {
            null
        }
        return launch?.let { governed(it, n, calls) }
    }

    // What the agent of a launch does with its token: its session, the catalog, a governed gate, the two ends.
    private suspend fun TestServer.governed(launch: LaunchResponse, n: Int, calls: Calls): Started? {
        val token = launch.agentToken
        val sessionId = "s-$n"
        val steps = listOf<Pair<String, suspend () -> HttpResponse>>(
            "POST /sessions" to { client.post(SESSIONS) { bearer(token); json(session(sessionId, launch.launchId)) } },
            "GET /gates/catalog/signed" to { client.get(CATALOG) { bearer(token) } },
            "POST /gates (governed)" to { client.post(GATES) { bearer(token); json(ask(sessionId, n)) } },
            "POST /sessions/{id}/end" to { client.post("$SESSIONS/$sessionId/end") { bearer(token); json("{}") } },
            "DELETE /launches/{id}" to { client.delete("$LAUNCHES/${launch.launchId}") { bearer(token) } },
        )
        val whole = steps.all { (route, call) -> calls.timed(route, call) { } != null }
        return if (whole) Started(launch.launchId, token, sessionId) else null
    }

    // A launch as the 1 000 of the leak test run it: created, its session started and ended, then the launch ended.
    private suspend fun TestServer.shortPath(person: Person, n: Int) {
        val launch = launchOf(person)
        val sessionId = "s-leak-$n"
        startSession(launch.agentToken, launch.launchId, sessionId)
        val end = client.post("$SESSIONS/$sessionId/end") { bearer(launch.agentToken); json("{}") }
        assertTrue(end.status.isSuccess(), end.bodyAsText())
        val gone = client.delete("$LAUNCHES/${launch.launchId}") { bearer(launch.agentToken) }
        assertEquals(HttpStatusCode.NoContent, gone.status, gone.bodyAsText())
    }

    // The launch's transaction alone, as 100 people of one organization launch at once: the repository on a pool of 20
    // connections of a role granted the serving role, so that the organization's advisory lock is really waited on.
    private suspend fun TestServer.contendedLaunches(team: Team): List<Duration> =
        database.withRole(poolSize = CONTENDED_POOL) { role, pool ->
            database.superuser.execute("GRANT ${database.servingRole} TO $role").getOrThrow()
            val launches = PostgresLaunchesRepository(pool)
            coroutineScope {
                team.people.map { person ->
                    async {
                        val start = TimeSource.Monotonic.markNow()
                        val refused = launches.create(stored(team.org.id, person.userId), LaunchLimit.DEFAULT)
                        val took = start.elapsedNow()
                        assertEquals(null, refused, "No launch of the hundred is over the limit")
                        took
                    }
                }.awaitAll()
            }
        }

    /** A write route measured: its whole calls one at a time, and the statements of one call with their plans. */
    private class WriteMeasure(val measure: ReadMeasure) {
        fun headline(): String {
            val times = measure.latencies
            return "${measure.name}: ${measure.statements.size} statements, whole call p50 ${measure.p50}, " +
                "p95 ${measure.p95}, max ${times.last()} (${times.size} calls one at a time)"
        }

        fun describe(): String = buildString {
            appendLine("== ${headline()}")
            measure.statements.forEach { appendLine("-- ${it.sql}") }
            measure.plans.forEach { appendLine(it).appendLine() }
        }
    }

    private suspend fun TestServer.measureWrite(
        name: String,
        call: suspend HttpClient.() -> HttpResponse,
    ): WriteMeasure {
        suspend fun checked() {
            val answer = client.call()
            check(answer.status.isSuccess()) { "$name answers ${answer.status}: ${answer.bodyAsText()}" }
        }
        repeat(WRITE_WARM_UPS) { checked() }
        val latencies = List(WRITE_CALLS) {
            val start = TimeSource.Monotonic.markNow()
            checked()
            start.elapsedNow()
        }.sorted()
        val statements = driver.record { checked() }
        val median = latencies[latencies.size / 2]
        return WriteMeasure(ReadMeasure(name, statements, explain(statements), latencies, median))
    }

    // The plan of every statement of a call, under EXPLAIN without ANALYZE: nothing runs, so a taken key is no matter.
    // Each transaction of the call is replayed in one of its own, its organization set, then rolled back.
    private suspend fun TestServer.explain(statements: List<SentStatement>): List<String> {
        val plans = mutableListOf<String>()
        val groups = statements.fold(mutableListOf<MutableList<SentStatement>>()) { groups, sent ->
            if (groups.lastOrNull()?.last()?.transaction == sent.transaction) {
                groups.last() += sent
            } else {
                groups += mutableListOf(sent)
            }
            groups
        }
        for (group in groups) {
            val tx = database.driver.begin().getOrThrow()
            try {
                plans += tx.plansOf(group)
            } finally {
                tx.rollback().getOrThrow()
            }
        }
        return plans
    }

    private suspend fun Transaction.plansOf(group: List<SentStatement>): List<String> = group.mapNotNull { sent ->
        if (PLANNED.containsMatchIn(sent.sql) && !SETUP.containsMatchIn(sent.sql)) {
            fetchAll(sent.fresh("EXPLAIN ")).getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
        } else {
            execute(sent.fresh("")).getOrThrow()
            null
        }
    }

    private fun SentStatement.fresh(prefix: String): Statement {
        val copy = Statement.create(prefix + sql)
        statement?.namedParametersValues.orEmpty().forEach { (name, value) -> copy.bind(name, value) }
        return copy
    }

    private fun stored(orgId: Uuid, userId: Uuid) = Launch(
        id = "lch_" + Uuid.random().toHexString(), orgId = orgId, userId = userId, dev = "load", repo = REPO,
        agent = "claude-code", model = null, createdAt = now, expiresAt = now + 24.hours,
    )

    private suspend fun TestServer.launchOf(person: Person): Started {
        val created = client.post(LAUNCHES) { bearer(person.cliToken); json(LAUNCH) }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val launch = created.body<LaunchResponse>()
        return Started(launch.launchId, launch.agentToken, "")
    }

    private suspend fun TestServer.startSession(agentToken: String, launchId: String, id: String) {
        val started = client.post(SESSIONS) { bearer(agentToken); json(session(id, launchId)) }
        assertTrue(started.status.isSuccess(), started.bodyAsText())
    }

    // An organization of [size] people, the first its owner, the others joined by an invitation; each with a CLI token.
    private suspend fun TestServer.team(prefix: String, size: Int): Team {
        val owner = user("$prefix-0")
        val org = Org(Uuid.random(), "$prefix-team", "$prefix team", null, null, now)
        koin.get<OrgsRepository>().create(org, Member(Uuid.random(), org.id, owner, OrgRole.OWNER, now)) { false }
        val invitations = koin.get<InvitationsRepository>()
        val members = (1 until size).map { n ->
            val userId = user("$prefix-$n")
            val invitation =
                Invitation(Uuid.random(), org.id, null, OrgRole.MEMBER, owner, now, now + 1.hours, null, 0, null)
            invitations.create(invitation, "hash-" + Uuid.random().toHexString())
            assertTrue(invitations.accept(invitation, Member(Uuid.random(), org.id, userId, OrgRole.MEMBER, now), now))
            "$prefix-$n" to userId
        }
        val people = (listOf("$prefix-0" to owner) + members).map { (login, userId) ->
            Person(login, userId, cliToken(userId, org.id))
        }
        return Team(org, people)
    }

    private suspend fun TestServer.user(login: String): Uuid = koin.get<ResolveUserUseCase>()(
        VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
    ).id

    private suspend fun TestServer.cliToken(userId: Uuid, orgId: Uuid): String {
        val secret = "osc_" + Uuid.random().toHexString()
        val token = ApiToken(
            Uuid.random(), userId, orgId, "cli", TokenKind.CLI, CLI_SCOPES, "hint", null, now, now + 1.hours, null,
            null,
        )
        koin.get<ApiTokensRepository>().create(token, koin.get<SecretHasher>().hash(secret))
        return secret
    }

    // An accepted workflow whose gate covers a push to main: the first governed tool call asks it.
    private suspend fun TestServer.release(org: Org) {
        val workflow = Workflow(
            id = "release",
            name = "Release",
            domain = "acme",
            nodes = listOf(
                WorkflowNode(id = "t", kind = WorkflowNodeKind.TRIGGER, label = "push"),
                WorkflowNode(
                    id = "g", kind = WorkflowNodeKind.GATE, label = "Push to main", approvers = listOf("lead"),
                ),
            ),
            edges = listOf(WorkflowEdge("t", "g")),
            status = WorkflowStatus.ACCEPTED,
            foundAt = "2026-11-01T00:00:00.000Z",
        )
        val raw = PackageCompiler.json.encodeToJsonElement(Workflow.serializer(), workflow).jsonObject
        koin.get<CompilerRepository>().saveWorkflow(org.id, "release", raw, null, Authorship("load", now))
    }

    // Another organization's launches, agent tokens and sessions, past 20 000 rows each: a scan reads them all.
    private suspend fun TestServer.fill(team: Team) {
        val other = Uuid.random()
        val owner = team.people.first().userId
        val fixtures = database.superuser
        fixtures.execute(
            "INSERT INTO launches_launches (org_id, id, user_id, dev, repo, agent, model, created_at, expires_at) " +
                "SELECT CAST('$other' AS uuid), 'lch' || i, gen_random_uuid(), 'dev', '$REPO', 'claude-code', NULL, " +
                "now() - i * INTERVAL '1 minute', now() - i * INTERVAL '1 minute' + INTERVAL '1 day' " +
                "FROM generate_series(1, $FILLED_ROWS) AS i",
        ).getOrThrow()
        fixtures.execute(
            "INSERT INTO identity_api_tokens (id, user_id, org_id, name, kind, scopes, hint, agent_session_id, " +
                "secret_hash, created_at, expires_at, revoked_at) SELECT gen_random_uuid(), CAST('$owner' AS uuid), " +
                "CAST('$other' AS uuid), 'agent', 'AGENT', 'agent' || chr(58) || 'session', 'x', 'lch' || i, " +
                "'fill-' || i, now() - i * INTERVAL '1 minute', now() + INTERVAL '1 day', now() " +
                "FROM generate_series(1, $FILLED_ROWS) AS i",
        ).getOrThrow()
        fixtures.execute(
            "INSERT INTO sessions_sessions (org_id, id, root_id, segment, launch_id, agent, dev, repo, started_at, " +
                "ended_at, last_seen_at, cost_usd) SELECT CAST('$other' AS uuid), 'f' || i, 'f' || i, 0, 'lch' || i, " +
                "'claude-code', 'dev', '$REPO', now() - i * INTERVAL '1 minute', now() - i * INTERVAL '1 minute', " +
                "now() - i * INTERVAL '1 minute', 0 FROM generate_series(1, $FILLED_ROWS) AS i",
        ).getOrThrow()
        FILLED.forEach { fixtures.execute("VACUUM ANALYZE $it").getOrThrow() }
    }

    private suspend fun TestServer.deadlocks(): Long = database.superuser.fetchAll(
        "SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()",
    ).getOrThrow().first().get("deadlocks").asString().toLong()

    private suspend fun TestServer.activeAgentTokens(orgId: Uuid): Long = database.superuser.fetchAll(
        "SELECT COUNT(*) AS n FROM identity_api_tokens WHERE kind = 'AGENT' AND org_id = CAST('$orgId' AS uuid) " +
            "AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now())",
    ).getOrThrow().first().get("n").asString().toLong()

    private suspend fun TestServer.count(table: String, orgs: List<Uuid>, more: String): Long =
        database.superuser.fetchAll(
            "SELECT COUNT(*) AS n FROM $table WHERE org_id IN (" +
                orgs.joinToString { "CAST('$it' AS uuid)" } + ")$more",
        ).getOrThrow().first().get("n").asString().toLong()

    private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) =
        header(HttpHeaders.Authorization, "Bearer $token")

    private fun io.ktor.client.request.HttpRequestBuilder.json(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun summary(reads: List<ReadMeasure>, writes: List<WriteMeasure>): String = buildString {
        val statements = reads.sumOf { it.statements.size } + writes.sumOf { it.measure.statements.size }
        appendLine("statements of one whole launch path (8 calls): $statements")
        reads.forEach {
            appendLine(
                "${it.name}: ${it.statements.size} statements, database p50 ${it.p50}, p95 ${it.p95} " +
                    "($CONCURRENT_READERS concurrent), whole call ${it.http}",
            )
        }
        writes.forEach { appendLine(it.headline()) }
    }


    private fun write(name: String, report: String) {
        SystemFileSystem.createDirectories(Path("build/reports/perf"))
        SystemFileSystem.sink(Path("build/reports/perf/$name.txt")).buffered().use { it.writeString(report) }
        println(report)
    }

    private companion object {
        const val REPO = "acme/api"
        const val POLICY = "/api/v1/policy/agents?agent=claude-code&model=claude-opus-4-1"
        const val PACKAGE = "/api/v1/packages/current?repo=$REPO"
        const val LAUNCHES = "/api/v1/launches"
        const val SESSIONS = "/api/v1/sessions"
        const val CATALOG = "/api/v1/gates/catalog/signed?repo=$REPO"
        const val GATES = "/api/v1/gates"
        const val LAUNCH = """{"repo":"$REPO","agent":"claude-code","model":"claude-opus-4-1"}"""

        const val PEOPLE_FOR_ROUTES = 10
        const val MEASURED_CALLS = 30
        const val CONCURRENT = 100
        // TestDatabase.LARGEST_POOL: 50 ran CI's PostgreSQL out of connections beside the other tests' pools.
        const val CONTENDED_POOL = 20
        const val LEAK_PEOPLE = 50
        const val LAUNCHES_EACH = 10
        // Under the test pool's 4 connections: the leak test measures what is left, not the contention.
        const val LEAK_WIDTH = 3
        val CONCURRENCY_BUDGET = 150.seconds
        const val TEXT = 120
        const val SHA_LENGTH = 40
        const val FILLED_ROWS = 20_000
        val FILLED = setOf("launches_launches", "identity_api_tokens", "sessions_sessions")
        const val WRITE_WARM_UPS = 3
        const val WRITE_CALLS = 20
        const val CONCURRENT_READERS = 20
        val PLANNED = Regex("^[\\s(]*(SELECT|WITH|VALUES|INSERT|UPDATE|DELETE)\\b", RegexOption.IGNORE_CASE)
        val SETUP = Regex("set_config\\(|pg_advisory", RegexOption.IGNORE_CASE)
        const val P50 = 0.5
        const val P95 = 0.95

        fun line(route: String, times: List<Duration>): String {
            val sorted = times.sorted()
            fun at(q: Double) = sorted[ceil(q * sorted.size).toInt() - 1]
            return "$route: p50 ${at(P50)}, p95 ${at(P95)}, max ${sorted.last()} (${sorted.size} calls)"
        }

        fun session(id: String, launchId: String) =
            """{"id":"$id","launchId":"$launchId","agent":"claude-code","repo":"$REPO"}"""

        // The hook sends the commits a push sends: the enforced gate of the release workflow refuses a push without.
        fun ask(sessionId: String, n: Int) =
            """{"sessionId":"$sessionId","repo":"$REPO","class":"push-main","tool":"Bash",""" +
                """"command":"git push origin main # $n","push":{"remote":"origin",""" +
                """"refs":[{"ref":"refs/heads/main","sha":"${n.toString().padStart(SHA_LENGTH, '0')}"}]}}"""
    }
}
