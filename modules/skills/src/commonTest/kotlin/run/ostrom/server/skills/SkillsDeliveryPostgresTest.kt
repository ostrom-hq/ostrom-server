// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.skills

import run.ostrom.server.kernel.database.inOrg
import run.ostrom.server.skills.fakes.ADA
import run.ostrom.server.skills.fakes.BEN
import run.ostrom.server.skills.fakes.ORG_A
import run.ostrom.server.skills.fakes.ORG_B
import run.ostrom.server.skills.fakes.ZOE
import run.ostrom.server.skills.fakes.call
import run.ostrom.server.skills.fakes.field
import run.ostrom.server.skills.fakes.publication
import run.ostrom.server.skills.fakes.testIdentities
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals

/** What workstations report, the integrity of a download, and the confinement of each organization's rows. */
class SkillsDeliveryPostgresTest {
    private val get = HttpMethod.Get
    private val post = HttpMethod.Post
    private val put = HttpMethod.Put

    private fun reports(vararg rows: String) = """{"installations": [${rows.joinToString()}]}"""

    private fun row(name: String, status: String = "installed", version: String? = null, digest: String? = null) =
        """{"name": "$name", "status": "$status"""" + version?.let { """, "version": "$it"""" }.orEmpty() +
            digest?.let { """, "digest": "$it"""" }.orEmpty() + "}"

    @Test
    fun aWorkstationReportsWhatItInstalled() = serverTest(listOf(SkillsModule), testIdentities()) { server ->
        val client = server.client
        client.call(post, "owner", "$TEAM/review/versions", publication("1.0.0", pin = true, allMembers = true))
        client.call(post, "ben-cli", "/api/v1/me/skills/bens/versions", publication("1.0.0", pin = true))

        // Only the CLI's token is a workstation.
        for (token in listOf("ada", "ada-key", "ada-app", "ada-odd")) {
            val refused = client.call(put, token, INSTALLATIONS, reports(row("review")))
            assertEquals(409 to "device_required", refused.status to refused.error, token)
        }
        assertEquals(204, client.call(put, "ada-cli", INSTALLATIONS, reports()).status)
        val installed = reports(row("review", version = "1.0.0", digest = DIGEST))
        assertEquals(204, client.call(put, "ada-cli", INSTALLATIONS, installed).status)
        val mine = client.call(get, "ada-cli", INSTALLATIONS).list().single()
        assertEquals(
            listOf("dev-1", "Ada's Mac", "review", "1.0.0", DIGEST, "installed"),
            listOf("deviceId", "deviceName", "name", "version", "digest", "status").map(mine::field),
        )
        assertEquals(204, client.call(put, "ada-cli", INSTALLATIONS, reports(row("review", "removed"))).status)
        assertEquals("removed", client.call(get, "ada-app", INSTALLATIONS).list().single().field("status"))
        assertEquals(204, client.call(put, "ada-cli", INSTALLATIONS, reports(row("review", version = "1.0.0"))).status)
        assertEquals(204, client.call(put, "ben-cli", INSTALLATIONS, reports(row("bens", "conflict"))).status)

        val refusals = listOf(
            reports(row("nope")) to (404 to "not_found"),
            reports(row("bens")) to (404 to "not_found"),
            reports(row("review", version = "9.9.9")) to (400 to "invalid_skill_installation"),
            reports(row("review", version = "1.0.0", digest = "sha256:" + "0".repeat(64))) to
                (400 to "invalid_skill_installation"),
            reports(row("review", digest = DIGEST)) to (400 to "invalid_skill_installation"),
            reports(row("review", "done")) to (400 to "invalid_skill_installation"),
            reports(row("review"), row("review")) to (400 to "invalid_skill_installation"),
            reports(*Array(501) { row("s-$it") }) to (400 to "invalid_skill_installation"),
            reports(row("Review")) to (400 to "invalid_skill_name"),
            reports(row("review", version = "-")) to (400 to "invalid_skill_version"),
            reports(row("review", version = "1.0.0", digest = "md5:x")) to (400 to "invalid_skill_installation"),
        )
        refusals.forEach { (body, expected) ->
            val answer = client.call(put, "ada-cli", INSTALLATIONS, body)
            assertEquals(expected, answer.status to answer.error, body.take(MESSAGE))
        }

        // An administrator reads a member's rows; a member only their own.
        val ada = client.call(get, "owner", "/api/v1/members/$ADA/skill-installations").list()
        assertEquals(listOf("review"), ada.map { it.field("name") })
        assertEquals(403, client.call(get, "ada", "/api/v1/members/$BEN/skill-installations").status)
        assertEquals(404, client.call(get, "owner", "/api/v1/members/$ZOE/skill-installations").status)
        assertEquals(403, client.call(get, "ada-key", INSTALLATIONS).status, "A key reads no workstation's rows")
        assertEquals(200, client.call(get, "ada", "/api/v1/members/$ADA/skill-installations").status)
    }

    @Test
    fun aDownloadWhoseFilesNoLongerMatchItsDigestIsRefused() =
        serverTest(listOf(SkillsModule), testIdentities()) { server ->
            val client = server.client
            client.call(post, "owner", "$TEAM/review/versions", publication("1.0.0", pin = true, allMembers = true))
            server.driver.inOrg(ORG_A) {
                val corrupt = Statement.create("UPDATE skills_files SET content = CAST(:c AS BYTEA)")
                execute(corrupt.bind("c", byteArrayOf(1))).getOrThrow()
            }
            val answer = client.call(get, "ada", "/api/v1/skill-delivery/review/1.0.0")
            assertEquals(500 to "skill_integrity", answer.status to answer.error)
        }

    @Test
    fun anotherOrganizationsRowsAreHidden() = serverTest(listOf(SkillsModule), testIdentities()) { server ->
        val client = server.client
        client.call(post, "owner", "$TEAM/review/versions", publication("1.0.0", pin = true, allMembers = true))
        client.call(put, "owner", "$TEAM/review/assignments/$ADA", """{"decision": "deny"}""")
        client.call(put, "ben-cli", INSTALLATIONS, reports(row("review", version = "1.0.0")))
        suspend fun count(org: kotlin.uuid.Uuid, table: String) = server.driver.inOrg(org) {
            fetchAll(Statement.create("SELECT COUNT(*) AS n FROM $table")).getOrThrow().first().get("n").asLong()
        }
        val tables =
            listOf("skills_skills", "skills_versions", "skills_files", "skills_assignments", "skills_installations")
        for (table in tables) {
            assertEquals(1L, count(ORG_A, table), table)
            // No WHERE org_id: the policy alone hides organization A's rows.
            assertEquals(0L, count(ORG_B, table), table)
        }
        // Organization B's owner sees nothing of A's catalog, under A's names.
        assertEquals(emptyList(), client.call(get, "zoe", TEAM).list())
        assertEquals(404, client.call(get, "zoe", "$TEAM/review/versions").status)
        assertEquals(404, client.call(get, "zoe", "/api/v1/skill-delivery/review/1.0.0").status)
        assertEquals(201, client.call(post, "zoe", "$TEAM/review/versions", publication("1.0.0")).status)
    }

    private companion object {
        const val TEAM = "/api/v1/skills"
        const val INSTALLATIONS = "/api/v1/skill-delivery/installations"
        const val DIGEST = "sha256:fed02854d013630baa3518562a6b42207967f7c141b6043ff847366365c20588"
        const val MESSAGE = 80
    }
}
