package dev.ostrom.server.artifacts

import dev.ostrom.server.artifacts.domain.ArtifactAccess
import dev.ostrom.server.artifacts.domain.ArtifactRules
import dev.ostrom.server.artifacts.domain.ArtifactSecretException
import dev.ostrom.server.artifacts.domain.ArtifactTooLargeException
import dev.ostrom.server.artifacts.domain.InvalidArtifactRequestException
import dev.ostrom.server.artifacts.domain.UploadedFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtifactRulesTest {
    private fun file(path: String, text: String = "x") = UploadedFile(path, text.encodeToByteArray())

    @Test
    fun accessLevelsReadTheirWireValues() {
        assertEquals(ArtifactAccess.LINK, ArtifactAccess.fromWire("link"))
        assertNull(ArtifactAccess.fromWire("public"))
    }

    @Test
    fun aTitleIsTrimmedAndBounded() {
        assertEquals("Report", ArtifactRules.title("  Report "))
        assertFailsWith<InvalidArtifactRequestException> { ArtifactRules.title("   ") }
        assertFailsWith<InvalidArtifactRequestException> { ArtifactRules.title("a".repeat(201)) }
        assertFailsWith<InvalidArtifactRequestException> { ArtifactRules.title("a\u0007b") }
    }

    @Test
    fun pathsAreRelativeAndPlain() {
        ArtifactRules.checkPath("index.html")
        ArtifactRules.checkPath("img/a-b_c.png")
        listOf("/etc/passwd", "../x", "a/./b", "a//b", "a b", "a/..", "x".repeat(201), "").forEach {
            assertFailsWith<InvalidArtifactRequestException>(it) { ArtifactRules.checkPath(it) }
        }
    }

    @Test
    fun aVersionIsCheckedBeforeAnythingIsStored() {
        ArtifactRules.check(listOf(file("index.html"), file("a.css")))
        assertFailsWith<InvalidArtifactRequestException> { ArtifactRules.check(emptyList()) }
        assertFailsWith<ArtifactTooLargeException> {
            ArtifactRules.check((0..ArtifactRules.MAX_FILES).map { file("f$it.txt") })
        }
        assertFailsWith<InvalidArtifactRequestException> { ArtifactRules.check(listOf(file("a"), file("a"))) }
        val big = UploadedFile("big.bin", ByteArray((ArtifactRules.MAX_FILE_BYTES + 1).toInt()))
        assertFailsWith<ArtifactTooLargeException> { ArtifactRules.check(listOf(big)) }
        val chunk = (ArtifactRules.MAX_FILE_BYTES).toInt()
        val many = (0..4).map { UploadedFile("c$it.bin", ByteArray(chunk)) }
        assertFailsWith<ArtifactTooLargeException> { ArtifactRules.check(many) }
        val leaked = file("notes.md", "token: ghp_" + "a".repeat(36))
        val failure = assertFailsWith<ArtifactSecretException> { ArtifactRules.check(listOf(leaked)) }
        assertTrue(failure.message.startsWith("notes.md"))
    }

    @Test
    fun secretsAreFoundInTextOnly() {
        listOf(
            "sk-ant-" + "a".repeat(30),
            "-----BEGIN RSA PRIVATE KEY-----",
            "AKIA" + "A".repeat(16),
            "ostrom_pat_" + "a".repeat(32),
        ).forEach { assertTrue(ArtifactRules.holdsSecret(it.encodeToByteArray()), it) }
        assertFalse(ArtifactRules.holdsSecret("a plain report".encodeToByteArray()))
        assertFalse(ArtifactRules.holdsSecret(byteArrayOf(0) + ("AKIA" + "A".repeat(16)).encodeToByteArray()))
    }

    @Test
    fun theEntryPageIsIndexThenTheFirstPage() {
        assertEquals("index.html", ArtifactRules.entry(listOf("b.html", "index.html")))
        assertEquals("a.htm", ArtifactRules.entry(listOf("z.md", "a.htm", "b.css")))
        assertEquals("docs/readme.md", ArtifactRules.entry(listOf("docs/readme.md", "data.json")))
        assertEquals("a.json", ArtifactRules.entry(listOf("b.png", "a.json")))
    }

    @Test
    fun typesComeFromTheExtension() {
        assertEquals("text/html; charset=utf-8", ArtifactRules.contentType("index.HTML"))
        assertEquals("image/png", ArtifactRules.contentType("img/a.png"))
        assertEquals("application/octet-stream", ArtifactRules.contentType("Makefile"))
        assertEquals("application/octet-stream", ArtifactRules.contentType("a.unknown"))
    }
}
