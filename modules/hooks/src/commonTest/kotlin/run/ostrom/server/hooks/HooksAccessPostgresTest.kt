// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.hooks

import run.ostrom.server.hooks.domain.Hook
import run.ostrom.server.hooks.domain.HooksRepository
import run.ostrom.server.hooks.fakes.ADA
import run.ostrom.server.hooks.fakes.BEN
import run.ostrom.server.hooks.fakes.DAN
import run.ostrom.server.hooks.fakes.ORG_A
import run.ostrom.server.hooks.fakes.ORG_B
import run.ostrom.server.hooks.fakes.RecordingAudit
import run.ostrom.server.hooks.fakes.ZOE
import run.ostrom.server.hooks.fakes.call
import run.ostrom.server.hooks.fakes.field
import run.ostrom.server.hooks.fakes.publication
import run.ostrom.server.hooks.fakes.testIdentities
import run.ostrom.server.kernel.database.inOrg
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Who receives a hook, the administrator's changes, the integrity of a download and each organization's rows. */
class HooksAccessPostgresTest {
    private companion object {
        const val REVIEW = "/api/v1/hooks/review"
        const val ALL = """{"allMembers": true}"""
        const val NOT_ALL = """{"allMembers": false}"""
        const val ALLOW = """{"decision": "allow"}"""
        const val DENY = """{"decision": "deny"}"""

        fun pin(version: String) = """{"version": "$version"}"""
    }

    private val get = HttpMethod.Get
    private val post = HttpMethod.Post
    private val put = HttpMethod.Put
    private val delete = HttpMethod.Delete

    private suspend fun HttpClient.manifest(token: String) =
        call(get, token, "/api/v1/hook-delivery").list("hooks").map { it.field("name") + "@" + it.field("version") }

    private suspend fun HttpClient.pinned(name: String, allMembers: Boolean) {
        call(post, "owner", "/api/v1/hooks/$name/versions", publication("1.0.0", allMembers = allMembers))
        call(put, "owner", "/api/v1/hooks/$name/pin", pin("1.0.0"))
    }

    @Test
    fun theAudienceAndTheDecisionsDecideWhoReceivesIt() {
        val audit = RecordingAudit()
        serverTest(listOf(HooksModule), testIdentities(audit)) { server ->
            val client = server.client
            client.pinned("review", allMembers = true)
            assertEquals(listOf("review@1.0.0"), client.manifest("ben"))

            assertEquals("false", client.call(put, "owner", "$REVIEW/audience", NOT_ALL).text("allMembers"))
            assertEquals(200, client.call(put, "owner", "$REVIEW/audience", NOT_ALL).status, "Idempotent")
            assertEquals(emptyList(), client.manifest("ada"), "Assigned only: nobody is set to yes")
            val allowed = client.call(put, "owner", "$REVIEW/assignments/$ADA", ALLOW)
            assertEquals(200, allowed.status, "$allowed")
            assertEquals("allow" to "$ADA", allowed.text("decision") to allowed.text("memberId"))
            assertEquals(200, client.call(put, "owner", "$REVIEW/assignments/$ADA", ALLOW).status, "Idempotent")
            assertEquals(200, client.call(put, "dan", "$REVIEW/assignments/$BEN", DENY).status)
            assertEquals(listOf("review@1.0.0"), client.manifest("ada"))
            assertEquals(emptyList(), client.manifest("ben"))
            val decisions = client.call(get, "owner", "$REVIEW/assignments").list()
            assertEquals(listOf("$ADA" to "allow", "$BEN" to "deny"), decisions.map {
                it.field("memberId") to it.field("decision")
            })

            // An explicit no wins over all members; clearing it falls back on the audience.
            client.call(put, "owner", "$REVIEW/audience", ALL)
            assertEquals(emptyList(), client.manifest("ben"))
            assertEquals(204, client.call(delete, "owner", "$REVIEW/assignments/$BEN").status)
            assertEquals(204, client.call(delete, "owner", "$REVIEW/assignments/$BEN").status, "Idempotent")
            assertEquals(204, client.call(delete, "owner", "$REVIEW/assignments/$DAN").status, "Never set")
            assertEquals(listOf("review@1.0.0"), client.manifest("ben"))
            assertEquals(1, client.call(get, "owner", "$REVIEW/assignments").list().size)
            assertEquals(200, client.call(put, "owner", "$REVIEW/assignments/$BEN", DENY).status, "Set again")
            assertEquals(emptyList(), client.manifest("ben"))

            val stranger = client.call(put, "owner", "$REVIEW/assignments/$ZOE", ALLOW)
            assertEquals(404, stranger.status)
            val maybe = client.call(put, "owner", "$REVIEW/assignments/$ADA", """{"decision": "maybe"}""")
            assertEquals(400 to "invalid_hook_assignment", maybe.status to maybe.error)
            assertEquals(404, client.call(get, "owner", "/api/v1/hooks/nope/assignments").status)
            assertEquals(404, client.call(put, "owner", "/api/v1/hooks/nope/assignments/$ADA", ALLOW).status)
            for (token in listOf("ada", "ci")) {
                assertEquals(403, client.call(put, token, "$REVIEW/assignments/$ADA", ALLOW).status, token)
                assertEquals(403, client.call(put, token, "$REVIEW/audience", ALL).status, token)
                assertEquals(403, client.call(get, token, "$REVIEW/assignments").status, token)
            }
            val events = audit.records.map { it.event }.filter { it.startsWith("hook.access") || "audience" in it }
            assertEquals(
                listOf(
                    "hook.audience.all_members", "hook.audience.assigned_only", "hook.access.allowed",
                    "hook.access.denied", "hook.audience.all_members", "hook.access.cleared", "hook.access.denied",
                ),
                events,
            )
        }
    }

    @Test
    fun onlyAnAdministratorPinsAndUnpins() = serverTest(listOf(HooksModule), testIdentities()) { server ->
        val client = server.client
        client.pinned("review", allMembers = true)
        client.call(post, "owner", "$REVIEW/versions", publication("1.1.0"))

        assertEquals("1.1.0", client.call(put, "owner", "$REVIEW/pin", pin("1.1.0")).text("pinnedVersion"))
        assertEquals(listOf("review@1.1.0"), client.manifest("ada"), "An update")
        assertEquals("1.0.0", client.call(put, "dan", "$REVIEW/pin", pin("1.0.0")).text("pinnedVersion"), "A rollback")
        val unpinned = client.call(delete, "owner", "$REVIEW/pin")
        assertEquals(200 to null, unpinned.status to unpinned.text("pinnedVersion"))
        assertEquals(200, client.call(delete, "owner", "$REVIEW/pin").status, "Idempotent")
        assertEquals(emptyList(), client.manifest("ada"), "Unpinned: delivered to nobody")

        assertEquals(404, client.call(put, "owner", "$REVIEW/pin", pin("7.0.0")).status)
        assertEquals(404, client.call(put, "owner", "/api/v1/hooks/nope/pin", pin("1.0.0")).status)
        assertEquals(404, client.call(delete, "owner", "/api/v1/hooks/nope/pin").status)
        assertEquals(404, client.call(put, "owner", "/api/v1/hooks/nope/audience", ALL).status)
        assertEquals(400, client.call(put, "owner", "$REVIEW/pin", """{"version": "-"}""").status)
        assertEquals(400, client.call(put, "owner", "/api/v1/hooks/BAD/pin", pin("1")).status)
        for (token in listOf("ada", "ci", "ada-cli", "ada-app")) {
            assertEquals(403, client.call(put, token, "$REVIEW/pin", pin("1.0.0")).status, token)
            assertEquals(403, client.call(delete, token, "$REVIEW/pin").status, token)
        }
    }

    @Test
    fun aVersionWhoseFilesNoLongerMatchItsDigestIsNeverServed() =
        serverTest(listOf(HooksModule), testIdentities()) { server ->
            val client = server.client
            client.pinned("review", allMembers = true)
            server.driver.inOrg(ORG_A) {
                val corrupt = Statement.create("UPDATE hooks_files SET content = CAST(:c AS BYTEA)")
                execute(corrupt.bind("c", byteArrayOf(1))).getOrThrow()
            }
            val download = client.call(get, "ada", "/api/v1/hook-delivery/review/1.0.0")
            assertEquals(500 to "hook_integrity", download.status to download.error)
            val read = client.call(get, "owner", "$REVIEW/versions/1.0.0")
            assertEquals(500 to "hook_integrity", read.status to read.error)
        }

    @Test
    fun anotherOrganizationsRowsAreHidden() = serverTest(listOf(HooksModule), testIdentities()) { server ->
        val client = server.client
        client.pinned("review", allMembers = true)
        client.call(put, "owner", "$REVIEW/assignments/$ADA", DENY)
        suspend fun count(org: Uuid, table: String) = server.driver.inOrg(org) {
            fetchAll(Statement.create("SELECT COUNT(*) AS n FROM $table")).getOrThrow().first().get("n").asLong()
        }
        for (table in listOf("hooks_hooks", "hooks_versions", "hooks_files", "hooks_assignments")) {
            assertEquals(1L, count(ORG_A, table), table)
            // No WHERE org_id: the policy alone hides organization A's rows.
            assertEquals(0L, count(ORG_B, table), table)
        }
        // Organization B's owner sees nothing of A's catalog, under A's names.
        assertEquals(emptyList(), client.call(get, "zoe", "/api/v1/hooks").list())
        assertEquals(404, client.call(get, "zoe", "$REVIEW/versions").status)
        assertEquals(404, client.call(get, "zoe", "$REVIEW/versions/1.0.0").status)
        assertEquals(404, client.call(put, "zoe", "$REVIEW/pin", pin("1.0.0")).status)
        assertEquals(404, client.call(get, "zoe", "/api/v1/hook-delivery/review/1.0.0").status)
        assertEquals(201, client.call(post, "zoe", "$REVIEW/versions", publication("1.0.0")).status)
    }

    @Test
    fun theCatalogKeepsItsLimits() = serverTest(listOf(HooksModule), testIdentities()) { server ->
        val repository = server.koin.get<HooksRepository>()
        val now = Clock.System.now()
        repository.write(ORG_A) {
            repeat(100) { insertHook(Hook(Uuid.random(), ORG_A, "hook-$it", null, null, false, now, now)) }
        }
        val client = server.client
        val hooks = client.call(post, "owner", "/api/v1/hooks/one-more/versions", publication("1"))
        assertEquals(409 to "hook_limit", hooks.status to hooks.error)
        assertEquals(100, client.call(get, "owner", "/api/v1/hooks").list().size)

        // The 200th version is the last.
        repeat(199) { client.call(post, "owner", "/api/v1/hooks/hook-0/versions", publication("1.0.$it")) }
        assertEquals(201, client.call(post, "owner", "/api/v1/hooks/hook-0/versions", publication("1.0.199")).status)
        val versions = client.call(post, "owner", "/api/v1/hooks/hook-0/versions", publication("2.0.0"))
        assertEquals(409 to "hook_version_limit", versions.status to versions.error)
        assertEquals(200, client.call(get, "owner", "/api/v1/hooks/hook-0/versions").list("versions").size)
    }
}
