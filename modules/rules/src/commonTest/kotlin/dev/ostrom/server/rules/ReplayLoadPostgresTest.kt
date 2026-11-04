// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules

import dev.ostrom.core.telemetry.metrics.MeterImpl
import dev.ostrom.core.telemetry.metrics.MetricData
import dev.ostrom.server.compiler.CompilerModule
import dev.ostrom.server.gates.GatesModule
import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.gates.domain.repositories.GateHistory
import dev.ostrom.server.gates.domain.repositories.GatesRepository
import dev.ostrom.server.instance.InstanceModule
import dev.ostrom.server.kernel.database.withOrganization
import dev.ostrom.server.kernel.observability.ProcessMetrics
import dev.ostrom.server.receipts.ReceiptsModule
import dev.ostrom.server.rules.domain.repositories.RulesRepository
import dev.ostrom.server.rules.domain.services.EveryRule
import dev.ostrom.server.rules.domain.services.GateReplay
import dev.ostrom.server.rules.fakes.ReplayFixtures.action
import dev.ostrom.server.rules.fakes.ReplayFixtures.gated
import dev.ostrom.server.rules.fakes.ReplayFixtures.push
import dev.ostrom.server.rules.fakes.RulesCallers
import dev.ostrom.server.rules.fakes.RulesCallers.orgA
import dev.ostrom.server.rules.fakes.RulesCallers.orgB
import dev.ostrom.server.rules.fakes.RulesFixtures
import dev.ostrom.server.rules.fakes.call
import dev.ostrom.server.rules.infrastructure.RepositoryAnalysisEngine
import dev.ostrom.server.rules.presentation.ReplayPage
import dev.ostrom.server.testing.SentStatement
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.benchTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.module
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The replay under load, on a real PostgreSQL under the serving role: an organization of 1 500 gated rules, each
 * covering every action with 20 approvers and a statement of 2 000 characters (the worst case), replayed on its 1 000
 * newest boundary actions, among older ones and another organization's rows. Measured and written to
 * `build/reports/perf/rules-replay.txt`: the worker's duration, the resident memory of the process while it runs (the
 * pod's limit is 256 Mi; Linux reads the resident set, the JVM gives its heap in use), the size of the stored items,
 * and the plans of the replay's reads, which never scan a table.
 */
class ReplayLoadPostgresTest {
    private val modules = listOf(InstanceModule, GatesModule, ReceiptsModule, CompilerModule, RulesModule)

    @Test
    fun aThousandActionsUnderFifteenHundredRulesStayWithinBudget() =
        benchTest(modules, module { includes(RulesCallers.overrides(), RulesCallers.gateSessions) }) { server ->
            seed(server)
            val started = server.call("POST", "/api/v1/analysis/replay", "admin-a")
            assertEquals(HttpStatusCode.Accepted, started.status, started.bodyAsText())
            val memory = MemorySampler()
            var before = 0L
            lateinit var statements: List<SentStatement>
            // Timed alone on the server: no other test process's bench times or loads it meanwhile (exclusively).
            val took = server.database.exclusively {
                before = memory.read()
                coroutineScope {
                    val sampler = launch(Dispatchers.Default) { memory.sample() }
                    val start = TimeSource.Monotonic.markNow()
                    statements = server.driver.record { server.koin.get<RepositoryAnalysisEngine>().runNext() }
                    start.elapsedNow().also { sampler.cancelAndJoin() }
                }
            }
            val page = server.call("GET", "/api/v1/analysis/replay", "admin-a")
            assertEquals(HttpStatusCode.OK, page.status)
            val items = page.body<ReplayPage>().items
            val stored = Json.encodeToString(JsonArray.serializer(), JsonArray(items)).encodeToByteArray().size
            val plans = plans(server, statements)
            val report = "replay: ${items.size} actions x $RULES rules in $took; resident memory ${mib(before)} " +
                "before, peak ${mib(memory.peak())} (+${mib(memory.peak() - before)}); items ${mib(stored.toLong())} " +
                "stored; ${statements.size} statements\n" + plans.joinToString("\n\n")
            write("rules-replay", report)

            assertEquals(ACTIONS, items.size, "The 1 000 newest actions of the window")
            assertTrue(items.all { it.getValue("ruleCount").jsonPrimitive.content == "$RULES" }, "Every rule counts")
            assertTrue(plans.none { "Seq Scan on gates_gates" in it || "Seq Scan on rules_rules" in it }, report)
            assertTrue(stored < MAX_STORED, "Items over ${mib(MAX_STORED.toLong())}:\n$report")
            assertTrue(took < MAX_DURATION, "Over $MAX_DURATION:\n$report")
            // A pod rests at about 112 Mi of its 256 (JOURNAL 03/11, relay leg of chart-load): a replay, one at a time
            // on a server, leaves it 44 Mi for its requests. Only the resident set says so: the JVM's heap in use
            // counts its garbage until it collects (reported, not budgeted).
            val growth = memory.peak() - before
            val over = "Resident memory up by over ${mib(MAX_GROWTH)}:\n$report"
            assertTrue(!residentSetMeasured || growth < MAX_GROWTH, over)
        }

    @Test
    fun eachStepOfTheReplayReportsItsResidentMemory() =
        benchTest(modules, module { includes(RulesCallers.overrides(), RulesCallers.gateSessions) }) { server ->
            seed(server)
            val memory = MemorySampler()
            val steps = mutableListOf("seeded: ${mib(memory.read())}")
            fun step(name: String) {
                steps += "$name: ${mib(memory.read())}"
            }
            val rules = EveryRule.gated(server.koin.get<RulesRepository>(), orgA)
            step("${rules.size} rules read")
            val prepared = GateReplay.prepare(rules)
            step("rules prepared")
            val actions = server.koin.get<GateHistory>().recent(orgA, RulesFixtures.epoch - 30.days, ACTIONS)
            step("${actions.size} actions read")
            val items = coroutineScope {
                val sampler = launch(Dispatchers.Default) { memory.sample() }
                GateReplay.replayAll(actions, prepared, SelfApprovalPolicy.NEVER).also { sampler.cancelAndJoin() }
            }
            step("replayed (peak ${mib(memory.peak())})")
            val text = Json.encodeToString(JsonArray.serializer(), JsonArray(items))
            step("items written out (${mib(text.length.toLong() * 2)} of text)")
            write("rules-replay-steps", steps.joinToString("\n"))
            assertEquals(ACTIONS, items.size)
        }

    // The measured organization: its rules, its window's actions and older ones; then another organization's rows,
    // copied, so that a scan of a table would read far more than the organization's own rows.
    private suspend fun seed(server: TestServer) {
        val rules = server.koin.get<RulesRepository>()
        rules.transaction(orgA) {
            (1..RULES).forEach { n ->
                val approvers = (0 until APPROVERS).map { "rev-${(n + it) % REVIEWERS}" }
                val statement = "Rule $n: " + "a push to main waits for a review. ".repeat(STATEMENT_REPEAT)
                insertRule(gated(id = "rule_${n.toString().padStart(4, '0')}", approvers = approvers)
                    .copy(statement = statement.take(STATEMENT_LENGTH)))
            }
        }
        val gates = server.koin.get<GatesRepository>()
        val recent = (1..RECENT).map { it.minutes }
        val old = (1..OLD).map { 31.days + it.minutes }
        (recent + old).chunked(CHUNK).forEach { chunk ->
            gates.transaction {
                chunk.forEach { ago ->
                    // A command of its own: no two actions share their rules' coverage, the worst case.
                    val subject = push(command = "git push origin main # $ago")
                    insertGate(action(subject = subject).copy(key = "k-$ago", createdAt = RulesFixtures.epoch - ago))
                }
            }
        }
        val fixtures = server.database.superuser
        fixtures.execute(
            "INSERT INTO gates_gates ($GATE_COLUMNS) SELECT gen_random_uuid(), CAST('$orgB' AS uuid), " +
                "gate_key || '-' || i, ${GATE_COLUMNS.substringAfter("gate_key, ")} FROM gates_gates " +
                "CROSS JOIN generate_series(1, $COPIES) AS i WHERE org_id = CAST('$orgA' AS uuid)",
        ).getOrThrow()
        fixtures.execute(
            "INSERT INTO rules_rules ($RULE_COLUMNS) SELECT CAST('$orgB' AS uuid), id || '-' || i, " +
                "${RULE_COLUMNS.substringAfter("org_id, id, ")} FROM rules_rules " +
                "CROSS JOIN generate_series(1, $COPIES) AS i WHERE org_id = CAST('$orgA' AS uuid)",
        ).getOrThrow()
        fixtures.execute("VACUUM ANALYZE gates_gates").getOrThrow()
        fixtures.execute("VACUUM ANALYZE rules_rules").getOrThrow()
    }

    // The replay's reads of the rules and the gates, again under `EXPLAIN (ANALYZE, BUFFERS)`, as the serving role.
    private suspend fun plans(server: TestServer, statements: List<SentStatement>): List<String> =
        statements.filter { sent ->
            sent.sql.trimStart().startsWith("SELECT") && ("gates_gates" in sent.sql || "rules_rules" in sent.sql)
        }.map { sent ->
            val explain = Statement.create("EXPLAIN (ANALYZE, BUFFERS) ${sent.sql}")
            sent.statement?.namedParametersValues.orEmpty().forEach { (name, value) -> explain.bind(name, value) }
            server.driver.withOrganization(orgA) { tx ->
                tx.fetchAll(explain).getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
            }
        }

    private fun write(name: String, report: String) {
        SystemFileSystem.createDirectories(Path("build/reports/perf"))
        SystemFileSystem.sink(Path("build/reports/perf/$name.txt")).buffered().use { it.writeString(report) }
        println(report)
    }

    private fun mib(bytes: Long): String = "${bytes / MIB} MiB"

    /** The resident memory of this process, read as the server's own gauge reads it, and its peak while sampled. */
    @OptIn(ExperimentalAtomicApi::class)
    private class MemorySampler {
        private val meter = MeterImpl(Clock.System).also { ProcessMetrics.register(it) }
        private val highest = AtomicLong(0)

        fun read(): Long = meter.collect().filterIsInstance<MetricData.Gauge>()
            .firstOrNull { it.name == "process.memory.usage" }?.points?.singleOrNull()?.value?.toLong() ?: 0

        suspend fun sample() {
            while (true) {
                val now = read()
                if (now > highest.load()) highest.store(now)
                delay(SAMPLE_EVERY)
            }
        }

        fun peak(): Long = maxOf(highest.load(), read())
    }

    private companion object {
        const val RULES = 1_500
        const val ACTIONS = 1_000
        const val RECENT = 1_500
        const val OLD = 3_000
        const val COPIES = 5
        const val CHUNK = 500
        const val APPROVERS = 20
        const val REVIEWERS = 50
        const val STATEMENT_LENGTH = 2_000
        const val STATEMENT_REPEAT = 60
        const val MAX_STORED = 4 * 1024 * 1024
        const val MAX_GROWTH = 100L * 1024 * 1024
        const val MIB = 1024 * 1024
        val MAX_DURATION: Duration = 30.seconds
        val SAMPLE_EVERY: Duration = 0.01.seconds
        const val GATE_COLUMNS = "id, org_id, gate_key, subject, node, label, mode, status, asked_by, asked_via, " +
            "session_ids, launch_ids, shown_digest, evidence, decided_by, decided_at, self_approved, receipt_id, " +
            "reserved_by, reserved_at, consumed_at, released_at, created_at"
        const val RULE_COLUMNS = "org_id, id, statement, output, rule_group, scope, sources, why, strength, " +
            "confidence, status, found_by, found_at, review_by, decided_by, decided_at, origin, gate, updated_at"
    }
}
