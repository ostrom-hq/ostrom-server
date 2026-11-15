// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.cloud.domain.CloudRuntime
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.runners.domain.CloudConversationException
import run.ostrom.server.runners.domain.CloudFileRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a client's file name and bytes may become on a host's disk (TeamCode's `CloudFileRulesTest`). */
class CloudFileRulesTest {
    private fun clean(raw: String?) = CloudFileRules.sanitizeName(raw)

    @Test
    fun aNameIsNeverAPath() {
        assertEquals("passwd", clean("../../etc/passwd"))
        assertEquals("passwd", clean("/etc/passwd"))
        assertEquals("evil.txt", clean("..\\..\\Windows\\System32\\evil.txt"))
        assertEquals("evil.txt", clean("C:\\Users\\me\\evil.txt"))
        assertEquals("share.txt", clean("\\\\server\\share.txt"))
        assertEquals("file", clean(".."))
        assertEquals("file", clean("../"))
        assertEquals("file", clean("."))
        assertEquals("file", clean(""))
        assertEquals("file", clean(null))
        assertEquals("file", clean("///"))
        // Whatever is left contains no separator, no dot segment and nothing a shell or Windows reads specially.
        for (raw in listOf("a/../../b", "..\\..", "x/./y", "/", "\\", "a\u0000/../b", "....//....//etc")) {
            val name = clean(raw)
            assertFalse(
                '/' in name || '\\' in name || name == "." || name == ".." || name.startsWith("."),
                "$raw -> $name",
            )
        }
    }

    @Test
    fun controlFormatAndForbiddenCharactersAreRemoved() {
        assertEquals("ab.png", clean("a\u0000b.png"), "NUL")
        assertEquals("ab.txt", clean("a\nb\r.txt"))
        assertEquals("invoice_gpj.exe", clean("invoice_\u202Egpj.exe"), "A right-to-left override cannot disguise it")
        assertEquals("ab.txt", clean("a\u200Bb.txt"), "Zero-width space")
        assertEquals("a_b_c_d_e_f_g.txt", clean("a<b>c:d\"e|f?g.txt"))
        assertEquals("a_.txt", clean("a*.txt"))
        assertEquals("rf.txt", clean("-rf.txt"), "Never read as an option")
        assertEquals("hidden", clean(".hidden"))
        assertEquals("report", clean("report. . ."), "Windows drops trailing dots and spaces")
        assertEquals("a b.txt", clean("  a \t  b.txt  "))
        assertEquals("ab", clean("a\u007Fb"), "DEL")
        assertEquals("a b", clean("a\u00A0b"), "A no-break space is a space")
    }

    @Test
    fun unicodeIsKeptNormalized() {
        assertEquals("Été ✓.md", clean("Été ✓.md"))
        // A decomposed é becomes the composed one, so one name is one file on every file system.
        assertEquals("\u00e9t\u00e9.txt", clean("e\u0301te\u0301.txt"))
        assertEquals("日本語のファイル.pdf", clean("日本語のファイル.pdf"))
        assertEquals("a.txt", clean("a\uD800.txt"), "A lone surrogate is dropped")
    }

    @Test
    fun windowsDeviceNamesArePrefixed() {
        val devices = listOf(
            "CON", "con", "PRN", "AUX", "NUL", "nul.txt", "COM1", "com9.log", "LPT1", "LPT¹", "CONIN$", "CON .txt",
        )
        for (device in devices) assertTrue(clean(device).startsWith("_"), "$device -> ${clean(device)}")
        assertEquals("CONSOLE.txt", clean("CONSOLE.txt"))
        assertEquals("COM10", clean("COM10"))
    }

    @Test
    fun aLongNameIsCutOnACharacterKeepingItsExtension() {
        val name = clean("a".repeat(500) + ".png")
        assertEquals(CloudFileRules.MAX_NAME_BYTES, name.encodeToByteArray().size)
        assertTrue(name.endsWith(".png"))
        val wide = clean("é".repeat(300) + ".pdf")
        assertTrue(wide.encodeToByteArray().size <= CloudFileRules.MAX_NAME_BYTES)
        assertTrue(wide.endsWith(".pdf"))
        assertTrue(wide.removeSuffix(".pdf").all { it == 'é' })
        val emoji = clean("😀".repeat(100))
        assertTrue(emoji.encodeToByteArray().size <= CloudFileRules.MAX_NAME_BYTES)
        assertEquals(emoji, emoji.encodeToByteArray().decodeToString(), "No split character")
        // An extension too long to keep is cut with the rest.
        val noExtension = clean("a".repeat(200) + "." + "b".repeat(20))
        assertEquals(CloudFileRules.MAX_NAME_BYTES, noExtension.encodeToByteArray().size)
        assertFalse('.' in noExtension)
        val refusal = assertFailsWith<CloudConversationException> {
            clean("a".repeat(CloudFileRules.MAX_RAW_NAME_CHARS + 1))
        }
        assertEquals("invalid_file_name", refusal.type)
    }

    @Test
    fun theTypeComesFromTheBytes() {
        fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
        assertEquals("image/png", CloudFileRules.magicType(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)))
        assertEquals("image/jpeg", CloudFileRules.magicType(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("image/gif", CloudFileRules.magicType("GIF89a...".encodeToByteArray()))
        assertEquals("image/gif", CloudFileRules.magicType("GIF87a...".encodeToByteArray()))
        assertEquals("image/webp", CloudFileRules.magicType("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".encodeToByteArray()))
        assertNull(CloudFileRules.magicType("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".encodeToByteArray()), "Not WebP")
        assertEquals("application/pdf", CloudFileRules.magicType("%PDF-1.7\n".encodeToByteArray()))
        assertNull(CloudFileRules.magicType(bytes(0x4D, 0x5A, 0x90, 0x00)), "An executable")
        assertNull(CloudFileRules.magicType(bytes(0x89, 0x50)), "Too short")
        assertEquals("text/markdown", CloudFileRules.textType("notes.MD"))
        assertEquals("text/markdown", CloudFileRules.textType("notes.markdown"))
        assertEquals("text/csv", CloudFileRules.textType("data.csv"))
        assertEquals("application/json", CloudFileRules.textType("a.json"))
        assertEquals("text/plain", CloudFileRules.textType("main.kt"))
    }

    @Test
    fun theExtensionFollowsTheType() {
        assertEquals("photo.png", CloudFileRules.finalName("photo.png", "image/png"))
        assertEquals("photo.JPEG", CloudFileRules.finalName("photo.JPEG", "image/jpeg"))
        assertEquals("photo.exe.png", CloudFileRules.finalName("photo.exe", "image/png"))
        assertEquals("photo.jpg", CloudFileRules.finalName("photo", "image/jpeg"))
        assertEquals("fake.png.txt", CloudFileRules.finalName("fake.png", "text/plain"), "Text never keeps an image's")
        assertEquals("main.kt", CloudFileRules.finalName("main.kt", "text/plain"))
        val dots = CloudFileRules.finalName(".".repeat(200) + "x", "image/png")
        assertEquals("file.x.png", dots, "Nothing left but dots")
        val long = CloudFileRules.finalName(clean("a".repeat(500) + ".exe"), "application/pdf")
        assertTrue(long.endsWith(".pdf"))
        assertTrue(long.encodeToByteArray().size <= CloudFileRules.MAX_NAME_BYTES)
    }

    @Test
    fun textIsCheckedAcrossParts() {
        val text = "Été ✓ 😀".encodeToByteArray()
        // Every split, including inside a multi-byte character.
        for (cut in 1 until text.size) {
            val check = CloudFileRules.TextCheck()
            check.feed(text.copyOfRange(0, cut))
            check.feed(text.copyOfRange(cut, text.size))
            assertTrue(check.finish(), "cut at $cut")
        }
        assertFalse(CloudFileRules.TextCheck().apply { feed(byteArrayOf(0x61, 0x00, 0x62)) }.finish(), "NUL")
        val malformed = CloudFileRules.TextCheck().apply { feed(byteArrayOf(0xC3.toByte(), 0x28)) }
        assertFalse(malformed.valid)
        malformed.feed("more".encodeToByteArray())
        assertFalse(malformed.finish(), "Malformed")
        assertFalse(
            CloudFileRules.TextCheck().apply { feed(byteArrayOf(0x61, 0xE2.toByte(), 0x9C.toByte())) }.finish(),
            "Cut short at the end",
        )
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x61)
        assertTrue(CloudFileRules.TextCheck().apply { feed(bom) }.finish(), "A byte-order mark")
        // More text than one pass of the decoder holds.
        assertTrue(CloudFileRules.TextCheck().apply { feed("é".repeat(20_000).encodeToByteArray()) }.finish())
    }

    @Test
    fun theLimitsComeFromTheRuntime() {
        val runtime = CloudRuntime(fileMaxMebibytes = 3, messageFileMebibytes = 4)
        assertEquals(3 * CloudFileRules.MEBIBYTE, CloudFileRules.fileLimit(runtime))
        assertEquals(4 * CloudFileRules.MEBIBYTE, CloudFileRules.messageLimit(runtime))
        assertEquals(20 * CloudFileRules.MEBIBYTE, CloudFileRules.fileLimit(CloudRuntime()))
        assertEquals(ErrorKind.PAYLOAD_TOO_LARGE, CloudFileRules.tooLarge(CloudFileRules.MEBIBYTE).kind)
        assertEquals(ErrorKind.UNSUPPORTED_MEDIA_TYPE, CloudFileRules.typeNotAllowed().kind)
        val quota = CloudFileRules.quota("Wait.", 12)
        assertEquals("upload_quota", quota.type)
        assertEquals(ErrorKind.RATE_LIMITED, quota.kind)
        assertEquals(12L, quota.retryAfterSeconds)
        assertNull(CloudFileRules.quota("Wait.").retryAfterSeconds)
    }
}
