// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns

import run.ostrom.core.api.models.Deliverable
import run.ostrom.server.workflowruns.domain.models.Deliverables
import run.ostrom.server.workflowruns.domain.models.InvalidDeliverableException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** What a delivery must be for each kind of deliverable (TeamCode `Deliverables`). */
class DeliverablesTest {
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun refused(deliverable: Deliverable, output: JsonElement): String {
        val refusal = assertFailsWith<InvalidDeliverableException> { Deliverables.validate(deliverable, output) }
        assertEquals("invalid_deliverable", refusal.type)
        return refusal.message
    }

    @Test
    fun anArtifactIsTheIdOfAnArtifactAsAString() {
        val artifact = Deliverable("artifact")
        assertEquals("art_1.v2:x-y", Deliverables.validate(artifact, JsonPrimitive("art_1.v2:x-y")))
        assertEquals("Deliver the id of an artifact.", refused(artifact, JsonPrimitive("not an id")))
        assertEquals("Deliver the id of an artifact.", refused(artifact, JsonPrimitive("a".repeat(65))))
        assertEquals("Deliver an artifact id as a string.", refused(artifact, JsonPrimitive(12)))
        assertEquals("Deliver an artifact id as a string.", refused(artifact, json("""{"id":"a"}""")))
    }

    @Test
    fun aUrlIsAnHttpsAddressOnItsDomainOrASubdomainOfIt() {
        val url = Deliverable("url")
        val pr = "https://github.com/acme/api/pull/1?tab=files#top"
        assertEquals(pr, Deliverables.validate(url, JsonPrimitive(pr)))
        assertEquals("https://[::1]:8443/x", Deliverables.validate(url, JsonPrimitive("https://[::1]:8443/x")))
        val github = Deliverable("url", domain = "GitHub.com.")
        for (address in listOf(pr, "HTTPS://api.GitHub.com:443/x", "https://github.com")) {
            assertEquals(address, Deliverables.validate(github, JsonPrimitive(address)), address)
        }
        assertEquals("Deliver an address on github.com.", refused(github, JsonPrimitive("https://evilgithub.com/x")))
        assertEquals("Deliver an address on github.com.", refused(github, JsonPrimitive("https://github.com.evil.io")))
        assertEquals("Deliver an https address.", refused(url, JsonPrimitive("http://github.com/x")))
        val spaces = "Deliver an https address of at most 2000 characters, without spaces."
        assertEquals(spaces, refused(url, JsonPrimitive("https://github.com/a b")))
        assertEquals(spaces, refused(url, JsonPrimitive("https://evil.com\\@github.com")))
        assertEquals(spaces, refused(url, JsonPrimitive("https://github.com/" + "x".repeat(Deliverables.MAX_URL))))
        assertEquals(spaces, refused(url, JsonPrimitive("")))
        val user = "Deliver an https address without a user name."
        assertEquals(user, refused(github, JsonPrimitive("https://github.com@evil.com/x")))
        val hostless = "Deliver an https address with a host."
        for (address in listOf("https:///x", "https://-bad-.com", "https://a..b", "https://:443")) {
            assertEquals(hostless, refused(url, JsonPrimitive(address)), address)
        }
        assertEquals("Deliver an https address as a string.", refused(url, json("[]")))
    }

    @Test
    fun aTextIsOneToThirtyTwoThousandCharacters() {
        val text = Deliverable("text")
        val longest = "x".repeat(Deliverables.MAX_TEXT)
        assertEquals(longest, Deliverables.validate(text, JsonPrimitive(longest)))
        val bound = "Deliver 1 to 32000 characters of text."
        assertEquals(bound, refused(text, JsonPrimitive(longest + "x")))
        assertEquals(bound, refused(text, JsonPrimitive(" \n ")))
        assertEquals("Deliver text as a string.", refused(text, JsonPrimitive(true)))
    }

    @Test
    fun aJsonIsAValueNotAStringWithinItsBoundAndItsSchema() {
        val free = Deliverable("json")
        assertEquals("""{"a":[1,true,null]}""", Deliverables.validate(free, json("""{ "a": [1, true, null] }""")))
        assertEquals("3", Deliverables.validate(free, JsonPrimitive(3)))
        assertEquals("Deliver a JSON value, not a string holding JSON.", refused(free, JsonPrimitive("{}")))
        val big = json("[\"${"x".repeat(Deliverables.MAX_JSON)}\"]")
        assertEquals("Deliver at most 64000 characters of JSON.", refused(free, big))
        val schema = json(
            """{"type":"object","required":["status",{}],"properties":{
            "status":{"type":"string","enum":["ok","ko",1,null,{}]},"meta":{"type":"object","required":"x"},
            "count":{"type":"integer"},"score":{"type":"number"},"done":{"type":"boolean"},
            "tags":{"type":"array","items":{"type":"string"}},"any":{"type":"array"},
            "free":{"type":"string"},"loose":{"enum":"x"},"odd":{"type":"null"},"raw":7}}""",
        )
        val typed = Deliverable("json", schema = schema)
        val right = """{"status":"ok","count":2,"score":1.5,"done":false,"tags":["a"],"any":[1],"free":"x","odd":1}"""
        assertEquals(right, Deliverables.validate(typed, json(right)))
        assertEquals("""{"status":"ko"}""", Deliverables.validate(typed, json("""{"status":"ko"}""")))
        // An object without properties, or whose required names are not a list, checks only that it is an object.
        val meta = """{"status":"ok","meta":{}}"""
        assertEquals(meta, Deliverables.validate(typed, json(meta)))
        val refusals = listOf(
            "[]" to "output must be an object.",
            """{}""" to "output.status is required.",
            """{"status":"maybe"}""" to "output.status must be one of ok, ko, 1.",
            """{"status":1}""" to "output.status must be a string.",
            """{"status":"ok","count":1.5}""" to "output.count must be an integer.",
            """{"status":"ok","count":"1"}""" to "output.count must be an integer.",
            """{"status":"ok","score":"x"}""" to "output.score must be a number.",
            """{"status":"ok","score":{}}""" to "output.score must be a number.",
            """{"status":"ok","score":true}""" to "output.score must be a number.",
            """{"status":["ok"]}""" to "output.status must be a string.",
            """{"status":"ok","meta":[]}""" to "output.meta must be an object.",
            """{"status":"ok","done":"true"}""" to "output.done must be true or false.",
            """{"status":"ok","done":1}""" to "output.done must be true or false.",
            """{"status":"ok","tags":"a"}""" to "output.tags must be a list.",
            """{"status":"ok","tags":["a",2]}""" to "output.tags[1] must be a string.",
        )
        for ((value, message) in refusals) assertEquals(message, refused(typed, json(value)), value)
        // A schema that is not an object, or names no type, checks nothing.
        assertEquals("1", Deliverables.validate(Deliverable("json", schema = json("[]")), JsonPrimitive(1)))
        assertEquals("1", Deliverables.validate(Deliverable("json", schema = json("{}")), JsonPrimitive(1)))
    }

    @Test
    fun aDeliverableOfAnUnknownTypeTakesNoDelivery() {
        val message = refused(Deliverable("video"), JsonPrimitive("x"))
        assertEquals("This step asks for a video deliverable, which nothing fits.", message)
    }
}
