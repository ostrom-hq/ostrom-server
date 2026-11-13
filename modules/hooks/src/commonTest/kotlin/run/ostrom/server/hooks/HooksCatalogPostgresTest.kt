// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.hooks

import run.ostrom.server.hooks.fakes.ADA
import run.ostrom.server.hooks.fakes.BEN
import run.ostrom.server.hooks.fakes.OWNER
import run.ostrom.server.hooks.fakes.RecordingAudit
import run.ostrom.server.hooks.fakes.SCRIPT
import run.ostrom.server.hooks.fakes.ZOE
import run.ostrom.server.hooks.fakes.call
import run.ostrom.server.hooks.fakes.definition
import run.ostrom.server.hooks.fakes.field
import run.ostrom.server.hooks.fakes.publication
import run.ostrom.server.hooks.fakes.testIdentities
import run.ostrom.server.hooks.fakes.upload
import run.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The hook catalog through its routes, on a real PostgreSQL: publication, the administrator's pin, delivery. */
class HooksCatalogPostgresTest {
    private companion object {
        const val HOOKS = "/api/v1/hooks"
        const val REVIEW = "/api/v1/hooks/review"
        const val DELIVERY = "/api/v1/hook-delivery"

        // sha256 over "hook\0Stop\0\0run.sh\060\n" and run.sh's file line (TeamCode's HookArtifacts.digest).
        const val DIGEST = "sha256:454e9a1de7ce8db04bf90dbdb1e82fcc34cc5583bf59c3a108dc74070b7fce22"

        // The same file on PreToolUse and Stop, matching Bash.
        const val MATCHED = "sha256:352fbef20561d4d659e0a1292a139d5fbcde4328247bd6c05bbf930799de1798"

        fun pin(version: String) = """{"version": "$version"}"""
    }

    private val get = HttpMethod.Get
    private val post = HttpMethod.Post
    private val put = HttpMethod.Put

    private suspend fun HttpClient.publish(token: String, name: String, body: String) =
        call(post, token, "$HOOKS/$name/versions", body)

    private suspend fun HttpClient.manifest(token: String) =
        call(get, token, DELIVERY).list("hooks").map { it.field("name") + "@" + it.field("version") }

    @Test
    fun aPublicationNeverDeliversUntilAnAdministratorPinsIt() {
        val audit = RecordingAudit()
        serverTest(listOf(HooksModule), testIdentities(audit)) { server ->
            val client = server.client
            val first = client.publish("owner", "review", publication("1.0.0"))
            assertEquals(201, first.status, "$first")
            assertEquals("true", first.text("created"))
            assertEquals(null, first.text("hook.pinnedVersion"), "Published is not delivered")
            assertFalse("pinnedVersion" in first.json.getValue("hook").toString(), "A null is omitted")
            assertEquals("false" to "false", first.text("hook.allMembers") to first.text("version.pinned"))
            assertEquals(DIGEST, first.text("version.digest"))
            assertEquals(listOf("10", "1", "$OWNER"), listOf("sizeBytes", "fileCount", "publishedBy").map {
                first.text("version.$it")
            })
            val fields = listOf("events.0", "command", "timeoutSeconds", "matcher")
            assertEquals(listOf("Stop", "run.sh", "60", null), fields.map { first.text("version.definition.$it") })

            val again = client.publish("owner", "review", publication("1.0.0"))
            assertEquals(200 to "false", again.status to again.text("created"), "A retry converges")
            val changed = client.publish("owner", "review", publication("1.0.0", upload("run.sh", "x", "executable")))
            assertEquals(409 to "hook_version_conflict", changed.status to changed.error)
            val retimed = client.publish("owner", "review", publication("1.0.0", definition = definition(extra =
                """, "timeoutSeconds": 30""")))
            assertEquals(409 to "hook_version_conflict", retimed.status to retimed.error, "The definition is digested")

            // A CI's key publishes and asks for all members while nothing is pinned; only the console pins.
            val ci = client.publish("ci", "review", publication("1.1.0", allMembers = true))
            assertEquals(201, ci.status, "$ci")
            assertEquals("true" to null, ci.text("hook.allMembers") to ci.text("hook.pinnedVersion"))
            assertEquals(emptyList(), client.manifest("ada"), "Nothing is pinned")
            assertEquals(403, client.call(put, "ci", "$REVIEW/pin", pin("1.1.0")).status, "No key ever pins")
            val pinned = client.call(put, "owner", "$REVIEW/pin", pin("1.1.0"))
            assertEquals("1.1.0", pinned.text("pinnedVersion"))
            assertEquals(200, client.call(put, "dan", "$REVIEW/pin", pin("1.1.0")).status, "Idempotent")
            assertEquals(listOf("review@1.1.0"), client.manifest("ada"))

            // Pinned: the key keeps publishing, all members included while the hook already is, but never widens it.
            assertEquals(201, client.publish("ci", "review", publication("1.2.0", allMembers = true)).status)
            client.call(put, "owner", "$REVIEW/audience", """{"allMembers": false}""")
            for (label in listOf("1.3.0", "1.2.0")) {
                val widened = client.publish("ci", "review", publication(label, allMembers = true))
                assertEquals(403 to "hook_audience_requires_administrator", widened.status to widened.error, label)
            }
            val versions = client.call(get, "owner", "$REVIEW/versions").list("versions").map { it.field("version") }
            assertEquals(listOf("1.2.0", "1.1.0", "1.0.0"), versions, "Refused whole: nothing stored")
            assertEquals(201, client.publish("ci", "review", publication("1.3.0")).status)
            // An administrator widens it at any time, at a publication or a retry.
            val admin = client.publish("owner", "review", publication("1.4.0", allMembers = true))
            assertEquals("true" to "1.1.0", admin.text("hook.allMembers") to admin.text("hook.pinnedVersion"))
            client.call(put, "owner", "$REVIEW/audience", """{"allMembers": false}""")
            val retry = client.publish("dan", "review", publication("1.4.0", allMembers = true))
            assertEquals(200 to "true", retry.status to retry.text("hook.allMembers"))
            assertEquals(201, client.publish("ci", "fresh", publication("1", allMembers = true)).status)

            val audit1 = audit.records.filter { it.details["credentialId"] == "key-1" }.map { it.event }
            val published = "hook.version.published"
            assertEquals(listOf(published, "hook.audience.all_members", published, published) +
                listOf("hook.created", published, "hook.audience.all_members"), audit1)
            assertTrue(audit.records.any { it.event == "hook.pinned" && it.details["credentialId"] == null })
        }
    }

    @Test
    fun aDefinitionAndItsArtifactAreChecked() = serverTest(listOf(HooksModule), testIdentities()) { server ->
        val client = server.client
        val matcher = """{"events": ["Stop", "PreToolUse"], "matcher": "Bash", "command": "run.sh"}"""
        val matched = client.publish("dan", "guard", publication("1.0.0", SCRIPT, definition = matcher))
        assertEquals(201, matched.status, "$matched")
        assertEquals(MATCHED, matched.text("version.digest"))
        assertEquals(listOf("PreToolUse", "Stop", "Bash"), listOf("events.0", "events.1", "matcher").map {
            matched.text("version.definition.$it")
        })
        val readme = upload("README.md", "# Hook")
        assertEquals(201, client.publish("dan", "guard", publication("1.1.0", SCRIPT, readme)).status)

        val invalid = "invalid_hook_definition"
        val refusals = listOf(
            publication("1", definition = definition("[]")) to invalid,
            publication("1", definition = definition("""["Nope"]""")) to invalid,
            publication("1", definition = definition("""["Stop", "Stop"]""")) to invalid,
            publication("1", definition = definition(extra = """, "matcher": """"")) to invalid,
            publication("1", definition = definition(extra = """, "matcher": "a\u0001"""")) to invalid,
            publication("1", definition = definition(extra = """, "matcher": "${"m".repeat(257)}"""")) to invalid,
            publication("1", definition = definition(extra = """, "timeoutSeconds": 0""")) to invalid,
            publication("1", definition = definition(extra = """, "timeoutSeconds": 601""")) to invalid,
            publication("1", definition = definition(command = "other.sh")) to invalid,
            publication("1", upload("run.sh", "#!/bin/sh\n")) to invalid,
            publication("1", definition = definition(command = "../run.sh")) to "invalid_hook_path",
            publication("1", upload("../run.sh", "x", "executable")) to "invalid_hook_path",
            publication("1", files = emptyArray()) to "invalid_hook_artifact",
            publication("1", upload("run.sh", "x", "symlink")) to "invalid_hook_artifact",
            """{"version": "1", "definition": ${definition()},""" +
                """ "files": [{"path": "run.sh", "contentBase64": "%"}]}""" to "invalid_hook_artifact",
            publication("1", *Array(51) { upload("f$it", "x", "executable") }) to "invalid_hook_artifact",
            publication("-1") to "invalid_hook_version",
        )
        refusals.forEach { (body, type) ->
            val answer = client.publish("dan", "fresh", body)
            assertEquals(400 to type, answer.status to answer.error, body.take(120))
        }
        // Only an owner, an admin or a key with hooks:publish publishes; refusals come before the body.
        val callers = listOf("ada" to 403, "ada-cli" to 403, "ada-key" to 403, "agent" to 403, "nobody" to 404)
        for ((token, status) in callers) {
            assertEquals(status, client.publish(token, "x", publication("1")).status, token)
        }
        val scopeless = client.publish("ci-skills", "x", publication("1"))
        assertEquals(403 to "credential_scope", scopeless.status to scopeless.error)
        val badName = client.publish("dan", "Bad_Name", publication("1"))
        assertEquals(400 to "invalid_hook_name", badName.status to badName.error)
        val large = upload("run.sh", "x".repeat(1024 * 1024 + 1), "executable")
        val tooLarge = client.publish("dan", "fresh", publication("1", large))
        assertEquals(413 to "hook_artifact_too_large", tooLarge.status to tooLarge.error)
        assertEquals(listOf("guard"), client.call(get, "owner", HOOKS).list().map { it.field("name") })
    }

    @Test
    fun anAdministratorReadsAVersionsCodeBeforePinningIt() =
        serverTest(listOf(HooksModule), testIdentities()) { server ->
            val client = server.client
            client.publish("dan", "guard", publication("1.1.0", SCRIPT, upload("README.md", "# Hook")))
            val read = client.call(get, "owner", "$HOOKS/guard/versions/1.1.0")
            assertEquals(200, read.status, "$read")
            assertEquals(listOf("README.md", "file", "run.sh", "executable", "IyEvYmluL3NoCg==", "10"), listOf(
                "files.0.path", "files.0.mode", "files.1.path", "files.1.mode", "files.1.contentBase64", "files.1.size",
            ).map(read::text))
            assertEquals("guard" to "1.1.0", read.text("name") to read.text("version"))
            assertEquals("run.sh", read.text("definition.command"))
            for (token in listOf("ada", "ci", "ada-cli")) {
                assertEquals(403, client.call(get, token, "$HOOKS/guard/versions/1.1.0").status, token)
            }
            assertEquals(404, client.call(get, "owner", "$HOOKS/guard/versions/9.0.0").status)
            assertEquals(404, client.call(get, "owner", "$HOOKS/nope/versions/1.0.0").status)
            assertEquals(400, client.call(get, "owner", "$HOOKS/guard/versions/-").status)
        }

    @Test
    fun theDeliveryIsTheMembersOwnOrAnAdministrators() = serverTest(listOf(HooksModule), testIdentities()) { server ->
        val client = server.client
        client.publish("owner", "review", publication("1.0.0", allMembers = true))
        client.publish("owner", "review", publication("1.1.0"))
        client.call(put, "owner", "$REVIEW/pin", pin("1.0.0"))

        val download = client.call(get, "ada", "$DELIVERY/review/1.0.0")
        assertEquals(200, download.status, "$download")
        assertEquals(DIGEST to "run.sh", download.text("digest") to download.text("files.0.path"))
        assertEquals("Stop", download.text("definition.events.0"))
        assertEquals(404, client.call(get, "ada", "$DELIVERY/review/1.1.0").status, "Only the pinned version")
        assertEquals(404, client.call(get, "ada", "$DELIVERY/nope/1.0.0").status)

        val manifest = client.call(get, "ada-cli", DELIVERY)
        assertEquals("$ADA", manifest.text("memberId"))
        assertEquals(listOf("review", "1.0.0", DIGEST, "10", "1", "run.sh"), listOf(
            "hooks.0.name", "hooks.0.version", "hooks.0.digest", "hooks.0.sizeBytes", "hooks.0.fileCount",
            "hooks.0.definition.command",
        ).map(manifest::text))
        assertEquals(listOf("review@1.0.0"), client.manifest("ada-key"))
        assertEquals(listOf("review@1.0.0"), client.manifest("ada-app"))
        assertEquals(200, client.call(get, "ada-key", "$DELIVERY/review/1.0.0").status)
        val pat = client.call(get, "ada-pat", DELIVERY)
        assertEquals(403 to "credential_scope", pat.status to pat.error)
        assertEquals(403, client.call(get, "agent", DELIVERY).status)
        assertEquals(403, client.call(get, "ada", "/api/v1/members/$BEN/hook-delivery").status)
        assertEquals(403, client.call(get, "ada-key", "/api/v1/members/$BEN/hook-delivery").status)
        val asAdmin = client.call(get, "owner", "/api/v1/members/$ADA/hook-delivery")
        assertEquals("$ADA" to 1, asAdmin.text("memberId") to asAdmin.list("hooks").size)
        assertEquals(404, client.call(get, "owner", "/api/v1/members/$ZOE/hook-delivery").status)
        assertEquals(200, client.call(get, "owner", "/api/v1/members/$OWNER/hook-delivery").status)

        // The catalog and its versions, for administrators in the console.
        val catalog = client.call(get, "dan", HOOKS).list()
        assertEquals(listOf("review" to "1.0.0"), catalog.map { it.field("name") to it.field("pinnedVersion") })
        for (token in listOf("ada", "ci", "ada-key")) assertEquals(403, client.call(get, token, HOOKS).status, token)
        val detail = client.call(get, "owner", "$REVIEW/versions")
        assertEquals("review", detail.text("hook.name"))
        val versions = detail.list("versions").map { it.field("version") to it.field("pinned") }
        assertEquals(listOf("1.1.0" to "false", "1.0.0" to "true"), versions)
        assertEquals("Stop", detail.text("versions.0.definition.events.0"))
        assertEquals(404, client.call(get, "owner", "$HOOKS/nope/versions").status)
        assertEquals(403, client.call(get, "ci", "$REVIEW/versions").status)
    }
}
