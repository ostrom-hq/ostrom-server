// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler

import run.ostrom.server.compiler.domain.services.CompilerDocs
import run.ostrom.server.compiler.domain.services.JsText
import run.ostrom.server.compiler.domain.services.PackageSigner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class JsTextTest {
    @Test
    fun prettyIsJsonStringifyWithTwoSpaces() {
        val value = Json.parseToJsonElement("""{"a":[1,"x",null,true],"b":{},"c":[],"d":{"e":1.5}}""")
        val expected = "{\n  \"a\": [\n    1,\n    \"x\",\n    null,\n    true\n  ],\n  \"b\": {},\n  \"c\": [],\n" +
            "  \"d\": {\n    \"e\": 1.5\n  }\n}"
        assertEquals(expected, JsText.pretty(value))
        assertEquals("\"a\\\"b\\n\"", JsText.quote("a\"b\n"))
    }

    @Test
    fun aSlugIsLowerCaseWithoutAccentsAndAtMostFortyCharacters() {
        assertEquals("ship-acme-api", JsText.slug("Ship   acme-api!", "workflow"))
        // As JavaScript: NFKD leaves the accent as a mark, which becomes a dash.
        assertEquals("de-ploiement-c-a-va", JsText.slug("Déploiement, ça va", "workflow"))
        assertEquals("u-ni-co-de-n-y", JsText.slug("Ünïcödé ñ ÿ", "workflow"))
        assertEquals("workflow", JsText.slug("—!", "workflow"))
        assertEquals("a".repeat(40), JsText.slug("a".repeat(50), "x"))
    }

    @Test
    fun isoHasMilliseconds() {
        assertEquals("2026-10-30T08:45:00.000Z", JsText.iso(Instant.parse("2026-10-30T08:45:00Z")))
        assertEquals("2026-10-30T08:45:00.120Z", JsText.iso(Instant.parse("2026-10-30T08:45:00.12Z")))
        assertEquals("2026-10-30T08:45:00.123Z", JsText.iso(Instant.parse("2026-10-30T08:45:00.123456Z")))
        assertEquals("202610300845", PackageSigner.stamp(Instant.parse("2026-10-30T08:45:59Z")))
    }

    @Test
    fun pathsAreOrderedAsLocaleCompare() {
        // The order Node gives with `a.localeCompare(b)`.
        val expected = listOf(
            "_x", "-a", ".claude/p", "10", "9", "a", "A", "a_b", "a-b", "a.md", "a/b", "ab", "aB", "Ab", "b", "B",
            "skills/b.md",
        )
        assertEquals(expected, expected.reversed().sortedWith(JsText.localeOrder))
        assertEquals(listOf("a", "a€"), listOf("a€", "a").sortedWith(JsText.localeOrder))
    }

    @Test
    fun documentsKeepWhatTheCompilerReadsAndToleratesTheRest() {
        val rule = CompilerDocs.rule(
            Json.parseToJsonElement(
                """{"id":"r","statement":"S.","scope":["a",1],"sources":[{"ref":"x"},{"kind":"k"},"y"],""" +
                    """"status":"accepted","strength":"required","confidence":0.7,"group":"Git"}""",
            ).jsonObject,
        )
        assertEquals(listOf("a"), rule.scope)
        assertEquals(listOf("x"), rule.sourceRefs)
        assertEquals(0.7, rule.confidence)
        val bare = CompilerDocs.rule(Json.parseToJsonElement("""{"confidence":"high","group":2}""").jsonObject)
        assertEquals("", bare.id)
        assertEquals(0.0, bare.confidence)
        assertNull(bare.group)
        assertEquals(emptyList(), bare.scope)
        assertEquals(0.0, CompilerDocs.rule(Json.parseToJsonElement("{}").jsonObject).confidence)
        val decision = CompilerDocs.decision(Json.parseToJsonElement("""{"id":"d","by":"a","at":"t"}""").jsonObject)
        assertEquals("", decision.subjectKind)
        assertNull(decision.receiptId)
        val blank = CompilerDocs.decision(Json.parseToJsonElement("{}").jsonObject)
        assertEquals(listOf("", "", ""), listOf(blank.id, blank.by, blank.at))
        val mixed = CompilerDocs.rule(Json.parseToJsonElement("""{"scope":["a",{"b":1},["c"],2]}""").jsonObject)
        assertEquals(listOf("a"), mixed.scope)
    }
}
