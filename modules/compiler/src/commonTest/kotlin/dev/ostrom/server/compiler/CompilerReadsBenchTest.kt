// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler

import dev.ostrom.core.api.models.Workflow
import dev.ostrom.core.api.models.WorkflowNodeKind.GATE
import dev.ostrom.core.api.models.WorkflowNodeKind.TRIGGER
import dev.ostrom.server.compiler.domain.services.PackageCompiler
import dev.ostrom.server.compiler.presentation.PackagesPage
import dev.ostrom.server.compiler.presentation.WorkflowsPage
import dev.ostrom.server.gates.GatesModule
import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.gates.presentation.GateScopes
import dev.ostrom.server.instance.InstanceModule
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalChannel
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.EnvironmentConfigSource
import dev.ostrom.server.kernel.config.LayeredConfigSource
import dev.ostrom.server.kernel.config.MapConfigSource
import dev.ostrom.server.receipts.ReceiptsModule
import dev.ostrom.server.testing.assertWithinBudget
import dev.ostrom.server.testing.benchTest
import dev.ostrom.server.testing.measureRead
import dev.ostrom.server.testing.writeReadReport
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.jsonObject
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The compiler's lists on 10 organizations of 1000 workflows and 1000 packages each, the packages spread over 50
 * repositories, each pinned: the workflows (all, accepted ones) and the packages (all, one repository's). Each keeps
 * the read budget (ReadBench.kt) and sends as many queries for 1000 packages as for 20 (no lookup per package); its
 * queries, plans and p95 are written to `build/reports/perf/compiler.txt`.
 */
class CompilerReadsBenchTest {
    private val orgs = List(ORGS) { Uuid.random() }
    private val acme = orgs.first()

    // The token is the person's login, an owner of acme signed in to the console.
    private val overrides = module {
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) = AuthenticatedPrincipal(
                    token, PrincipalKind.USER, setOf(GateScopes.ALL_USER), channel = PrincipalChannel.BROWSER,
                )
            }
        }
        single<GateActorResolver> {
            object : GateActorResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal): GateActor = GateActor(
                    principal.subjectId, acme, ActorKind.USER, personId = principal.subjectId, role = OrgRole.OWNER,
                )
            }
        }
        single<ConfigSource> {
            LayeredConfigSource(listOf(MapConfigSource(mapOf("OSTROM_PUBLIC_URL" to URL)), EnvironmentConfigSource()))
        }
    }

    private fun release(): String {
        val gate = node("g", GATE, label = "Push", approvers = listOf("lead"))
        val nodes = listOf(node("t", TRIGGER, label = "push"), gate)
        val workflow = workflow("release", nodes, chain("t", "g"))
        return PackageCompiler.json.encodeToJsonElement(Workflow.serializer(), workflow).jsonObject.toString()
    }

    // No `::` cast and no colon at all in the seeds: the driver would read `:x` as a named parameter. acme's workflow
    // and package, made by the API, are copied: 999 more of each, the packages over 50 repositories, each pinned.
    private fun copies(): List<String> = listOf(
        "INSERT INTO compiler_workflows (org_id, id, doc, version, updated_by, updated_at, created_at) " +
            "SELECT org_id, 'copy' || g, CAST(jsonb_set(CAST(doc AS JSONB), '{id}', to_jsonb('copy' || g)) AS TEXT), " +
            "version, updated_by, updated_at, created_at + g * INTERVAL '1 second' " +
            "FROM compiler_workflows, generate_series(1, ${PER_ORG - 1}) AS g",
        "INSERT INTO compiler_packages (org_id, content_digest, digest, version, repo, compiled_at, envelope, " +
            "counts, revoked) SELECT org_id, 'c' || g, 'd' || g, version, 'acme/r' || (g % $REPOS), compiled_at, " +
            "CAST(jsonb_set(jsonb_set(jsonb_set(CAST(envelope AS JSONB), '{repo}', to_jsonb('acme/r' || (g % " +
            "$REPOS))), '{digest}', to_jsonb('d' || g)), '{contentDigest}', to_jsonb('c' || g)) AS TEXT), counts, " +
            "FALSE FROM compiler_packages, generate_series(1, ${PER_ORG - 1}) AS g",
        "INSERT INTO compiler_pins (org_id, repo_key, content_digest, pinned_by, pinned_at) " +
            "SELECT org_id, 'acme/r' || r, 'c' || (r + $REPOS), 'lead', now() " +
            "FROM (SELECT DISTINCT org_id FROM compiler_packages) AS o, generate_series(0, ${REPOS - 1}) AS r",
    )

    private fun copiedTo(org: Uuid): List<String> = TABLES.map { table ->
        "INSERT INTO $table SELECT CAST('$org' AS uuid), " +
            "${COLUMNS.getValue(table)} FROM $table WHERE org_id = CAST('$acme' AS uuid)"
    }

    @Test
    fun theListsKeepTheirBudget() = benchTest(
        listOf(InstanceModule, ReceiptsModule, GatesModule, CompilerModule),
        overrides,
    ) { server ->
        val created = server.client.post("/api/v1/workflows") {
            bearerAuth("lead")
            setBody(TextContent(release(), ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals(HttpStatusCode.OK, server.client.get("/api/v1/packages/current") { bearerAuth("lead") }.status)
        val fixtures = server.database.superuser
        copies().forEach { fixtures.execute(it).getOrThrow() }
        orgs.drop(1).flatMap(::copiedTo).forEach { fixtures.execute(it).getOrThrow() }
        TABLES.forEach { fixtures.execute("VACUUM ANALYZE $it").getOrThrow() }

        // No lookup per package: 1000 packages send the statements of 20, checked before the bench replays them.
        val sent = PACKAGES.map { path -> server.driver.record { server.client.get(path) { bearerAuth("lead") } } }
        assertEquals(1, sent.map { it.size }.distinct().size, "Statements: ${sent.map { it.size }}")

        val reads = listOf(
            Read("/api/v1/workflows", PER_ORG, "compiler_workflows_pkey"),
            Read("/api/v1/workflows?status=accepted", PER_ORG, "compiler_workflows_pkey"),
            Read(PACKAGES[0], PER_ORG, "compiler_packages_recent"),
            Read(PACKAGES[1], PER_ORG / REPOS, "compiler_packages_by_repo"),
        )
        val measures = reads.map { read ->
            server.measureRead("GET ${read.path}") { get(read.path) { bearerAuth("lead") } }
        }
        writeReadReport("compiler", measures)
        measures.forEach { it.assertWithinBudget(setOf("compiler_workflows", "compiler_packages"), MAX_QUERIES) }
        reads.zip(measures).forEach { (read, measure) ->
            assertTrue(measure.plans.any { read.index in it }, measure.describe())
        }
        reads.forEach { read ->
            val page = server.client.get(read.path) { bearerAuth("lead") }
            val items = when {
                "workflows" in read.path -> page.body<WorkflowsPage>().items.size
                else -> page.body<PackagesPage>().items.size
            }
            assertEquals(read.items, items, read.path)
        }
        val pinned = server.client.get(PACKAGES[1]) { bearerAuth("lead") }.body<PackagesPage>().items
        assertEquals(listOf("c53"), pinned.filter { it.pinned }.map { it.contentDigest }, "acme/r3 pins c53")
    }

    /**
     * A read of the bench.
     *
     * @property path The route and its filters.
     * @property items The rows of its answer.
     * @property index The index its plan must name (compiler_packages_by_repo: CompilerMigrations version 2).
     */
    private data class Read(val path: String, val items: Int, val index: String)

    private companion object {
        const val ORGS = 10

        // The queries of the busiest read today (one per list, Build 41717982170):
        // one more is an N+1, on any runner.
        const val MAX_QUERIES = 1
        const val PER_ORG = 1000
        const val REPOS = 50
        const val URL = "https://ostrom.example/"
        val PACKAGES = listOf("/api/v1/packages", "/api/v1/packages?repo=acme%2Fr3")
        val TABLES = listOf("compiler_workflows", "compiler_decisions", "compiler_packages", "compiler_pins")
        val COLUMNS = mapOf(
            "compiler_workflows" to "id, doc, version, updated_by, updated_at, created_at",
            "compiler_decisions" to "id, subject_kind, subject_id, decided_by, decided_at, receipt_id",
            "compiler_packages" to "content_digest, digest, version, repo, compiled_at, envelope, counts, revoked, " +
                "revoked_by, revoke_reason",
            "compiler_pins" to "repo_key, content_digest, pinned_by, pinned_at",
        )
    }
}
