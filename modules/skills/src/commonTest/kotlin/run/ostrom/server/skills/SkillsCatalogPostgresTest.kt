// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.skills

import run.ostrom.server.skills.fakes.ADA
import run.ostrom.server.skills.fakes.BEN
import run.ostrom.server.skills.fakes.DAN
import run.ostrom.server.skills.fakes.OWNER
import run.ostrom.server.skills.fakes.RecordingAudit
import run.ostrom.server.skills.fakes.ZOE
import run.ostrom.server.skills.fakes.call
import run.ostrom.server.skills.fakes.field
import run.ostrom.server.skills.fakes.publication
import run.ostrom.server.skills.fakes.testIdentities
import run.ostrom.server.skills.fakes.upload
import run.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The team catalog through its routes, on a real PostgreSQL: publication, pins, audience, decisions, delivery. */
class SkillsCatalogPostgresTest {
    private companion object {
        const val REVIEW = "/api/v1/skills/review"
        const val DIGEST = "sha256:fed02854d013630baa3518562a6b42207967f7c141b6043ff847366365c20588"
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

    private suspend fun HttpClient.publish(token: String, name: String, body: String) =
        call(post, token, "/api/v1/skills/$name/versions", body)

    private suspend fun HttpClient.manifest(token: String) =
        call(get, token, "/api/v1/skill-delivery").list("skills").map {
            it.field("name") + "@" + it.field("version")
        }

    @Test
    @Suppress("LongMethod") // One end-to-end story through the HTTP API, read top to bottom.
    fun aVersionIsImmutableAndOnlyItsPinIsDelivered() {
        val audit = RecordingAudit()
        serverTest(listOf(SkillsModule), testIdentities(audit)) { server ->
            val client = server.client
            val first = client.publish("owner", "review", publication("1.0.0"))
            assertEquals(201, first.status, "$first")
            assertEquals("true", first.text("created"))
            assertEquals(null, first.text("skill.pinnedVersion"), "Published is not delivered")
            assertEquals("false", first.text("version.pinned"))
            assertEquals(DIGEST, first.text("version.digest"))
            assertEquals("7", first.text("version.sizeBytes"))
            assertEquals("$OWNER", first.text("version.publishedBy"))

            val again = client.publish("owner", "review", publication("1.0.0"))
            assertEquals(200, again.status, "A retry converges")
            assertEquals("false", again.text("created"))
            val changed = client.call(
                post, "owner", "$REVIEW/versions", publication("1.0.0", upload("SKILL.md", "# Other")),
            )
            assertEquals(409 to "skill_version_conflict", changed.status to changed.error)
            assertEquals(emptyList(), client.manifest("ada"), "Nothing is pinned")

            // A CI's key publishes, pins and opens to all members in one call; a key without the scopes cannot.
            val ci = client.call(
                post, "ci", "$REVIEW/versions", publication("1.1.0", pin = true, allMembers = true),
            )
            assertEquals(201, ci.status, "$ci")
            assertEquals("1.1.0", ci.text("skill.pinnedVersion"))
            assertEquals("true", ci.text("skill.allMembers"))
            assertEquals("true", ci.text("version.pinned"))
            val pinless = client.publish("ci-publish", "review", publication("2.0.0", pin = true))
            assertEquals(403 to "credential_scope", pinless.status to pinless.error)
            val audienceless =
                client.publish("ci-publish", "review", publication("2.0.0", allMembers = true))
            assertEquals(403 to "credential_scope", audienceless.status to audienceless.error)
            val script = upload("bin/run", "#!/bin/sh\n", "executable")
            val tool = publication("1.2.0", upload("SKILL.md", "# Skill", "file"), script)
            assertEquals(201, client.publish("ci-publish", "review", tool).status)
            val retry = publication("1.1.0", pin = true, allMembers = true)
            assertEquals(200, client.publish("ci", "review", retry).status)
            assertEquals(200, client.publish("ci-publish", "review", publication("1.0.0")).status)
            val back = client.publish("ci", "review", publication("1.0.0", pin = true))
            assertEquals("1.0.0", back.text("skill.pinnedVersion"), "A retry may pin a known version")
            assertEquals(listOf("review@1.0.0"), client.manifest("ada"))

            // Only an owner, an admin or a key with skills:publish publishes; refusals come before the body.
            for ((token, status) in listOf("ada" to 403, "ada-cli" to 403, "agent" to 403, "nobody" to 404)) {
                assertEquals(status, client.publish(token, "x", publication("1")).status, token)
            }
            val refusals = listOf(
                publication("1", upload("README.md", "x")) to "invalid_skill_artifact",
                publication("1", upload("SKILL.md", "x", "symlink")) to "invalid_skill_artifact",
                publication("1", upload("SKILL.md", "x", "fifo")) to "invalid_skill_artifact",
                """{"version": "1", "files": [{"path": "SKILL.md", "contentBase64": "%%%"}]}""" to
                    "invalid_skill_artifact",
                publication("1", upload("../SKILL.md", "x")) to "invalid_skill_path",
                publication("-1") to "invalid_skill_version",
            )
            refusals.forEach { (body, type) ->
                val answer = client.publish("dan", "fresh", body)
                assertEquals(400 to type, answer.status to answer.error, body)
            }
            val badName = client.publish("dan", "Bad_Name", publication("1"))
            assertEquals(400 to "invalid_skill_name", badName.status to badName.error)

            // The pinned version downloads, with what verifies it; another version does not.
            val download = client.call(get, "ada", "/api/v1/skill-delivery/review/1.0.0")
            assertEquals(200, download.status, "$download")
            assertEquals(DIGEST, download.text("digest"))
            assertEquals("SKILL.md", download.text("files.0.path"))
            assertEquals("IyBTa2lsbA==", download.text("files.0.contentBase64"))
            assertEquals("file", download.text("files.0.mode"))
            assertEquals(404, client.call(get, "ada", "/api/v1/skill-delivery/review/1.2.0").status)
            assertEquals(404, client.call(get, "ada", "/api/v1/skill-delivery/nope/1.0.0").status)

            // Who reads a delivery: the member (console, app, CLI, key with skills:read), or an administrator.
            assertEquals(listOf("review@1.0.0"), client.manifest("ada-key"))
            assertEquals(listOf("review@1.0.0"), client.manifest("ada-app"))
            val pat = client.call(get, "ada-pat", "/api/v1/skill-delivery")
            assertEquals(403 to "credential_scope", pat.status to pat.error)
            assertEquals(403, client.call(get, "ada", "/api/v1/members/$BEN/skill-delivery").status)
            val asAdmin = client.call(get, "owner", "/api/v1/members/$ADA/skill-delivery")
            assertEquals("$ADA", asAdmin.text("memberId"))
            assertEquals(1, asAdmin.list("skills").size)
            assertEquals(404, client.call(get, "owner", "/api/v1/members/$ZOE/skill-delivery").status)
            assertEquals(200, client.call(get, "owner", "/api/v1/members/$OWNER/skill-delivery").status)

            // The catalog and its versions, for administrators.
            assertEquals(1, client.call(get, "dan", "/api/v1/skills").list().size)
            assertEquals(403, client.call(get, "ada", "/api/v1/skills").status)
            val detail = client.call(get, "owner", "$REVIEW/versions")
            val versions = detail.list("versions")
            assertEquals(setOf("1.0.0", "1.1.0", "1.2.0"), versions.map { it.field("version") }.toSet())
            assertEquals(
                listOf("1.0.0"),
                versions.filter { it.field("pinned") == "true" }.map { it.field("version") },
            )
            assertEquals(404, client.call(get, "owner", "/api/v1/skills/nope/versions").status)

            // The CI's events name its key; a retry that changes nothing writes none.
            val ciEvents = audit.records.filter { it.details["credentialId"] == "key-1" }.map { it.event }
            val expected = listOf("skill.version.published", "skill.pinned", "skill.audience.all_members")
            assertEquals(expected + "skill.pinned", ciEvents)
            assertTrue(audit.records.any { it.event == "skill.created" && it.details["credentialId"] == null })
        }
    }

    @Test
    fun theAudienceAndTheDecisionsDecideWhoReceivesIt() = serverTest(listOf(SkillsModule), testIdentities()) { server ->
        val client = server.client
        client.publish("owner", "review", publication("1.0.0", pin = true, allMembers = true))
        assertEquals(listOf("review@1.0.0"), client.manifest("ben"))

        assertEquals("false", client.call(put, "owner", "$REVIEW/audience", NOT_ALL).text("allMembers"))
        assertEquals(200, client.call(put, "owner", "$REVIEW/audience", NOT_ALL).status)
        assertEquals(emptyList(), client.manifest("ada"), "Assigned only: nobody is set to yes")
        val allowed = client.call(put, "owner", "$REVIEW/assignments/$ADA", ALLOW)
        assertEquals(200, allowed.status, "$allowed")
        assertEquals("allow" to "$ADA", allowed.text("decision") to allowed.text("memberId"))
        assertEquals(200, client.call(put, "owner", "$REVIEW/assignments/$ADA", ALLOW).status)
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

        val stranger = client.call(put, "owner", "$REVIEW/assignments/$ZOE", ALLOW)
        assertEquals(404, stranger.status)
        val maybe = client.call(put, "owner", "$REVIEW/assignments/$ADA", """{"decision": "maybe"}""")
        assertEquals(400 to "invalid_skill_assignment", maybe.status to maybe.error)
        assertEquals(404, client.call(get, "owner", "/api/v1/skills/nope/assignments").status)
        assertEquals(403, client.call(put, "ada", "$REVIEW/assignments/$ADA", ALLOW).status)
        assertEquals(403, client.call(put, "ci", "$REVIEW/audience", ALL).status)
    }

    @Test
    fun pinsRetirementsAndRestorationsHaveTheirRules() = serverTest(listOf(SkillsModule), testIdentities()) { server ->
        val client = server.client
        client.publish("owner", "review", publication("1.0.0"))
        client.publish("owner", "review", publication("1.1.0", pin = true, allMembers = true))

        val pinned = client.call(delete, "owner", "$REVIEW/versions/1.1.0")
        assertEquals(409 to "skill_version_pinned", pinned.status to pinned.error)
        val retired = client.call(delete, "owner", "$REVIEW/versions/1.0.0")
        assertEquals("true" to "false", retired.text("retired") to retired.text("pinned"))
        assertEquals(200, client.call(delete, "owner", "$REVIEW/versions/1.0.0").status, "Idempotent")
        assertEquals(404, client.call(delete, "owner", "$REVIEW/versions/9.0.0").status)
        val old = client.call(put, "owner", "$REVIEW/pin", pin("1.0.0"))
        assertEquals(409 to "skill_version_retired", old.status to old.error)
        val republished = client.publish("owner", "review", publication("1.0.0"))
        assertEquals(409 to "skill_version_retired", republished.status to republished.error)
        val unknown = client.call(put, "owner", "$REVIEW/pin", pin("7.0.0"))
        assertEquals(404, unknown.status)

        assertEquals(null, client.call(delete, "owner", "$REVIEW/pin").text("pinnedVersion"))
        assertEquals(200, client.call(delete, "owner", "$REVIEW/pin").status, "Idempotent")
        assertEquals(emptyList(), client.manifest("ada"), "Unpinned: delivered to nobody")
        assertEquals("1.1.0", client.call(put, "ci", "$REVIEW/pin", pin("1.1.0")).text("pinnedVersion"))
        assertEquals(200, client.call(put, "owner", "$REVIEW/pin", pin("1.1.0")).status)
        val keyless = client.call(put, "ci-publish", "$REVIEW/pin", pin("1.1.0"))
        assertEquals(403 to "credential_scope", keyless.status to keyless.error)
        assertEquals(403, client.call(put, "ada", "$REVIEW/pin", pin("1.1.0")).status)
        assertEquals(403, client.call(delete, "ci", "$REVIEW/pin").status, "Unpinning is the console's")
        assertEquals(400, client.call(put, "owner", "$REVIEW/pin", """{"version": "-"}""").status)
        assertEquals(400, client.call(put, "owner", "/api/v1/skills/BAD/pin", """{"version": "1"}""").status)

        val gone = client.call(delete, "owner", REVIEW)
        assertEquals("true" to null, gone.text("retired") to gone.text("pinnedVersion"))
        assertEquals(emptyList(), client.manifest("ada"))
        assertEquals(200, client.call(delete, "owner", REVIEW).status, "Idempotent")
        val closed = client.publish("owner", "review", publication("2.0.0"))
        assertEquals(409 to "skill_retired", closed.status to closed.error)
        val frozen = client.call(put, "owner", "$REVIEW/pin", pin("1.1.0"))
        assertEquals(409 to "skill_retired", frozen.status to frozen.error)
        assertEquals("false", client.call(post, "owner", "$REVIEW/restore").text("retired"))
        assertEquals(200, client.call(post, "owner", "$REVIEW/restore").status, "Idempotent")
        assertEquals(404, client.call(post, "owner", "/api/v1/skills/nope/restore").status)
        assertEquals(403, client.call(delete, "ada", REVIEW).status)
    }
}
