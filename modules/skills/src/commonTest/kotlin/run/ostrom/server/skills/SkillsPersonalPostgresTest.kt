// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.skills

import run.ostrom.server.skills.domain.Skill
import run.ostrom.server.skills.domain.SkillsRepository
import run.ostrom.server.skills.fakes.ADA
import run.ostrom.server.skills.fakes.ORG_A
import run.ostrom.server.skills.fakes.RecordingAudit
import run.ostrom.server.skills.fakes.call
import run.ostrom.server.skills.fakes.field
import run.ostrom.server.skills.fakes.publication
import run.ostrom.server.skills.fakes.testIdentities
import run.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Members' personal skills (TeamCode #268), their promotion to the team, and the catalog's limits. */
class SkillsPersonalPostgresTest {
    private val get = HttpMethod.Get
    private val post = HttpMethod.Post
    private val put = HttpMethod.Put
    private val delete = HttpMethod.Delete

    private suspend fun HttpClient.manifest(token: String) =
        call(get, token, "/api/v1/skill-delivery").list("skills").map { it.field("name") + "@" + it.field("version") }

    @Test
    fun aPersonalSkillReachesItsOwnerOnlyUntilPromoted() {
        val audit = RecordingAudit()
        serverTest(listOf(SkillsModule), testIdentities(audit)) { server ->
            val client = server.client
            val mine = client.call(post, "ada-cli", "$ME/notes/versions", publication("1.0.0", pin = true))
            assertEquals(201, mine.status, "$mine")
            assertEquals("$ADA" to "1.0.0", mine.text("skill.ownerId") to mine.text("skill.pinnedVersion"))
            assertEquals(listOf("notes@1.0.0"), client.manifest("ada-cli"))
            assertEquals(emptyList(), client.manifest("ben"), "Nobody else receives it")

            // One name space: a name in use, the team's or another member's, is refused without naming its owner.
            client.call(post, "owner", "$TEAM/shared/versions", publication("1.0.0", pin = true, allMembers = true))
            val clashes = listOf("ben-cli" to "$ME/notes", "owner" to "$TEAM/notes", "ada-cli" to "$ME/shared")
            for ((token, path) in clashes) {
                val taken = client.call(post, token, "$path/versions", publication("9.0.0"))
                assertEquals(409 to "name_taken", taken.status to taken.error, path)
            }
            assertEquals(403, client.call(post, "ci", "$ME/notes/versions", publication("2.0.0")).status, "Never a key")
            val scopeless = client.call(post, "ada-pat", "$ME/notes/versions", publication("2.0.0"))
            assertEquals(403 to "credential_scope", scopeless.status to scopeless.error)
            assertEquals(201, client.call(post, "ada", "$ME/notes/versions", publication("1.1.0")).status)

            assertEquals(listOf("notes"), client.call(get, "ada-cli", ME).list().map { it.field("name") })
            assertEquals(2, client.call(get, "ada-app", "$ME/notes/versions").list("versions").size)
            assertEquals(404, client.call(get, "ben", "$ME/notes/versions").status, "Another member's is not found")
            assertEquals(404, client.call(get, "ada", "$ME/shared/versions").status, "A team skill is not hers")
            assertEquals(403, client.call(get, "ada-key", ME).status)

            assertEquals(200, client.call(put, "ada-cli", "$ME/notes/pin", PIN_1).status, "Already pinned")
            val pinned = client.call(put, "ada-cli", "$ME/notes/pin", """{"version": "1.1.0"}""")
            assertEquals("1.1.0", pinned.text("pinnedVersion"))
            assertEquals(null, client.call(delete, "ada-cli", "$ME/notes/pin").text("pinnedVersion"))
            assertEquals(emptyList(), client.manifest("ada-cli"))
            client.call(put, "ada-cli", "$ME/notes/pin", PIN_1)
            assertEquals("true", client.call(delete, "ada-cli", "$ME/notes").text("retired"))
            assertEquals(emptyList(), client.manifest("ada-cli"), "Retired: delivered to nobody")
            assertEquals("false", client.call(post, "ada-cli", "$ME/notes/restore").text("retired"))
            assertEquals(403, client.call(delete, "ada-pat", "$ME/notes").status)

            // The team's operations never touch a personal skill; an administrator sees it and promotes it.
            val personal = client.call(put, "owner", "$TEAM/notes/pin", PIN_1)
            assertEquals(409 to "skill_personal", personal.status to personal.error)
            assertEquals(409, client.call(delete, "owner", "$TEAM/notes").status)
            val catalog = client.call(get, "owner", TEAM).list().map { it.field("name") }
            assertEquals(setOf("notes", "shared"), catalog.toSet())
            assertEquals(2, client.call(get, "owner", "$TEAM/notes/versions").list("versions").size)
            assertEquals(403, client.call(post, "ada", "$TEAM/notes/promote").status)
            val team = client.call(post, "owner", "$TEAM/shared/promote")
            assertEquals(409 to "skill_not_personal", team.status to team.error)
            assertEquals(404, client.call(post, "owner", "$TEAM/nope/promote").status)
            val promoted = client.call(post, "owner", "$TEAM/notes/promote")
            assertEquals(200, promoted.status, "$promoted")
            val cleared = listOf("ownerId", "pinnedVersion", "allMembers").map(promoted::text)
            assertEquals(listOf(null, null, "false"), cleared)
            assertEquals(emptyList(), client.call(get, "ada-cli", ME).list())
            assertEquals(listOf("shared@1.0.0"), client.manifest("ada-cli"), "Promoted: delivered once pinned")
            assertTrue(audit.records.any { it.event == "skill.personal.created" })
            assertTrue(audit.records.any { it.event == "skill.promoted" })
        }
    }

    @Test
    fun theCatalogKeepsItsLimits() = serverTest(listOf(SkillsModule), testIdentities()) { server ->
        val repository = server.koin.get<SkillsRepository>()
        val now = Clock.System.now()
        fun skill(name: String, owner: String?) =
            Skill(Uuid.random(), ORG_A, name, owner, null, null, false, null, now, now)
        repository.write(ORG_A) {
            repeat(500) { insertSkill(skill("team-$it", null)) }
            repeat(50) { insertSkill(skill("mine-$it", "$ADA")) }
        }
        val client = server.client
        val team = client.call(post, "owner", "$TEAM/one-more/versions", publication("1"))
        assertEquals(409 to "skill_limit", team.status to team.error)
        val personal = client.call(post, "ada-cli", "$ME/one-more/versions", publication("1"))
        assertEquals(409 to "skill_limit", personal.status to personal.error)

        // The 200th version is the last.
        repeat(199) { client.call(post, "owner", "$TEAM/team-0/versions", publication("1.0.$it")) }
        assertEquals(201, client.call(post, "owner", "$TEAM/team-0/versions", publication("1.0.199")).status)
        val versions = client.call(post, "owner", "$TEAM/team-0/versions", publication("2.0.0"))
        assertEquals(409 to "skill_version_limit", versions.status to versions.error)
    }

    private companion object {
        const val TEAM = "/api/v1/skills"
        const val ME = "/api/v1/me/skills"
        const val PIN_1 = """{"version": "1.0.0"}"""
    }
}
