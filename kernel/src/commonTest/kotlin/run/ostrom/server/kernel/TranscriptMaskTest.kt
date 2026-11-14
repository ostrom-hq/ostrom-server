// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import run.ostrom.server.kernel.text.TranscriptMask
import run.ostrom.server.kernel.text.TranscriptMask.MASK
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TeamCode's `CloudConversationRulesTest` and `ArtifactRulesTest` cases of the credential shapes, ported. */
class TranscriptMaskTest {
    private fun masked(text: String) = TranscriptMask.mask(text)

    @Test
    fun credentialsAreMasked() {
        val secrets = listOf(
            "sk-ant-api03-${"a1".repeat(20)}", "sk-proj-${"Z9".repeat(12)}", "ghp_${"A1b2".repeat(6)}",
            "github_pat_${"x1".repeat(15)}", "ost_${"k3".repeat(20)}", "opk_${"k3".repeat(20)}",
            "AKIA${"ABCD1234".repeat(2)}", "xoxb-1234567890-abcdef",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJlLXZhbHVl",
        )
        for (secret in secrets) assertEquals("key $MASK end", masked("key $secret end"), secret)
        assertEquals("Authorization: Bearer $MASK", masked("Authorization: Bearer abcdefghijklmnop.qrs"))
        assertEquals("token $MASK", masked("token Ab3dEf9hIjKlMn"))
        assertEquals("basic $MASK", masked("basic dXNlcjpwYXNzd29yZA=="))
        assertEquals("Authorization: Bearer $MASK", masked("Authorization: Bearer correcthorsebatterystaple"))
        assertEquals("GITHUB_TOKEN=$MASK", masked("GITHUB_TOKEN=abcdefgh1234"))
        assertEquals("{\"api_key\": $MASK}", masked("{\"api_key\": \"abcdefgh1234\"}"))
        assertEquals("https://$MASK@example.com/repo.git", masked("https://user:pa55word@example.com/repo.git"))
        assertEquals(MASK, masked("-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----"))
        assertEquals(MASK, masked("-----BEGIN PRIVATE KEY-----\nabc"))
    }

    @Test
    fun ordinaryTextIsKept() {
        val texts = listOf(
            "task-management-feature-branch", "disk-usage-statistics-report", "etc_something_long_name_x",
            "max_tokens=4096", "token: refresh", "password: hidden", "Use a token budget of 100",
            "ask-for-review-before-merging-0", "the token management screen", "basic configuration options",
            "Bearer Authentication", "a token validation step", "Use the token configuration.",
            "Set up Basic Authentication.", "ost_migration_2026_plan_v2",
        )
        for (text in texts) assertEquals(text, masked(text))
    }

    @Test
    fun boundCutsAfterMasking() {
        val text = TranscriptMask.bound("x".repeat(20_000))

        assertEquals(TranscriptMask.MAX_EVENT_STRING_CHARS, text.length)
        assertTrue(text.endsWith(TranscriptMask.TRUNCATION_MARK))
        assertEquals("short $MASK", TranscriptMask.bound("short ghp_${"A1b2".repeat(6)}"))
    }

    @Test
    fun theKindOfACredentialIsNamed() {
        val leaks = mapOf(
            "const key = 'sk-ant-api03-abcdefghijklmnop1234';" to "provider_api_key",
            "token ghp_abcdefghijklmnopqrstuvwxyz0123456789" to "github_token",
            "github_pat_${"x1".repeat(15)}" to "github_token",
            "ost_${"k3".repeat(20)}" to "ostrom_credential",
            "AKIAABCDEFGHIJKLMNOP" to "aws_access_key",
            "xoxb-1234567890-abcdef" to "slack_token",
            "-----BEGIN RSA PRIVATE KEY-----\nMIIE\n-----END RSA PRIVATE KEY-----" to "private_key",
            "fetch('https://user:hunter2hunter2@example.com/data')" to "url_credentials",
            "Authorization: Bearer abcDEF123456789xyz" to "authorization_header",
        )
        for ((text, kind) in leaks) assertEquals(kind, TranscriptMask.detect("<html>$text</html>"), text)
        for (text in listOf(
            "const API_KEY = process.env.API_KEY_PLACEHOLDER;", "The token configuration is documented below.",
            "password: 'not-a-real-one'",
        )) {
            assertNull(TranscriptMask.detect(text), text)
        }
    }

    @Test
    fun theSecretScanIgnoresProse() {
        val prose = listOf(
            "Basic cross-browser support for every page", "Token\n----------------\nexplained below",
            "Basic\n================", "basic setup-instructions-here", "Basic input/output", "Token usage/limits",
            "token refresh_interval", "basic /api/v1/artifacts", "token getAccessToken",
            "Bearer /api/v1/artifacts/uploads", "token refresh_interval_v2_settings",
            "token getAccessTokenForCurrentUser", "Token getAccessTokenForCurrentUserSession",
            "token 2024-10-22-rotation-notes", "token 2024102212", "Token RefreshTokenSchedulerService",
            "token handleOAuth2Callback", "Bearer getAccessToken2", "Bearer /api/v1/items", "Bearer ./dist/app.js",
        )
        for (text in prose) assertNull(TranscriptMask.detect(text), text)
    }

    @Test
    fun theSecretScanFindsAuthorizationValuesAndCredentialsInUrls() {
        val found = mapOf(
            "Authorization: Bearer abc123def456ghi789" to "authorization_header",
            "Authorization: Basic dXNlcjpwYXNzd29yZA==" to "authorization_header",
            "postgres://app:hunter2secret@db.internal/app" to "url_credentials",
            "curl -H 'Authorization: token tok_4f8a9b1c2d3e5f6a7b8c'" to "authorization_header",
            "Authorization: Basic dXNlcjpwYXNzd29yZA" to "authorization_header",
            "Authorization: Bearer AbCdEfGhIjKlMnOpQrStUvWxYzAbCdEfGhIj_kLmN" to "authorization_header",
            "Authorization: Bearer Ab//Cd3fGhIjKlMnOpQrSt" to "authorization_header",
            "--url-https://u:hunter2pw@host" to "url_credentials",
            "Bearer eyJhbGciOiJIUzI1.eyJzdWIiOiIxMjM0.SflKxwRJSMeKKF2QT4" to "jwt",
            "Authorization: Bearer abc123def456" to "authorization_header",
            "Authorization: Bearer AbCdEfGhIjKlMnOpQrStUvWxYz" to "authorization_header",
            "Authorization: Token 4815162342108151623" to "authorization_header",
            "Authorization: Bearer /9j4AAQSkZJRgABAQEAYABgAAD" to "authorization_header",
        )
        for ((text, kind) in found) assertEquals(kind, TranscriptMask.detect(text), text)
        // A Basic value that is not base64 of a pair, or whose base64 is not text, is prose.
        assertNull(TranscriptMask.detect("Basic dXNlcnBhc3N3b3Jk"))
        assertNull(TranscriptMask.detect("Basic !!!notbase64!!!"))
        assertNull(TranscriptMask.detect("Basic /+/+/+/+/+/+"))
        assertNull(TranscriptMask.detect("Basic dXNlcjpwYXNzd29yZA==="), "More than two padding characters")
        assertNull(TranscriptMask.detect("Basic dXNlcjpwY"), "A length no base64 has")
        val control = java.util.Base64.getEncoder().encodeToString("ab:\u0001cd".encodeToByteArray())
        assertNull(TranscriptMask.detect("Basic $control"), "Control characters are no password")
        assertNull(TranscriptMask.detect("Basic //46//46//46"), "Bytes that are not text")
        assertNull(TranscriptMask.detect("token 123456789012"), "A short run of digits is a number")
        assertNull(TranscriptMask.detect("token refreshintervalx"), "A single lowercase word")
        assertNull(TranscriptMask.detect("token abcdefghijklmnopqrstuvwxyz"), "Letters of one case")
        // The transcript mask finds what the scanner finds.
        val jwt = "eyJhbGciOiJIUzI1.eyJzdWIiOiIxMjM0.SflKxwRJSMeKKF2QT4"
        assertFalse(jwt in masked("jwt=$jwt"))
        assertFalse("hunter2" in masked("--url-https://u:hunter2@host"))
    }

    @Test
    fun theScanStaysLinearOnCraftedText() {
        // Linearity is measured in characters read by the regex engine, not in time, so a loaded runner cannot fail it.
        val sizes = listOf(100_000, 200_000)
        val perChar = mutableMapOf<String, MutableList<Double>>()
        for (unit in listOf("a-", "sk-", "eyJ", "ost_", "a.", "-eyJ", "a", "x://a:")) {
            for (size in sizes) {
                val text = unit.repeat(size / unit.length + 1).take(size)
                val budget = 1_000L * size
                val detectReads = LongArray(1)
                val found = TranscriptMask.detect(CountingText(text, detectReads, budget))
                if (unit in listOf("a-", "sk-", "eyJ", "ost_")) assertNull(found, unit)
                val maskReads = LongArray(1)
                TranscriptMask.mask(text) { CountingText(it, maskReads, budget) }
                perChar.getOrPut("detect $unit") { mutableListOf() }.add(detectReads[0].toDouble() / size)
                perChar.getOrPut("mask $unit") { mutableListOf() }.add(maskReads[0].toDouble() / size)
            }
        }
        for ((name, reads) in perChar) {
            assertTrue(reads.all { it < 1_000 }, "$name reads a bounded number of characters per character: $reads")
            assertTrue(reads[1] < reads[0] * 1.5 + 1, "$name reads more per character on a longer input: $reads")
        }
    }

    /** A text that counts every character the regex engine reads from it. */
    private class CountingText(
        private val inner: CharSequence,
        private val reads: LongArray,
        private val budget: Long,
    ) : CharSequence {
        override val length get() = inner.length

        override fun get(index: Int): Char {
            check(++reads[0] <= budget) { "The scan read more than $budget characters (quadratic backtracking)" }
            return inner[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = inner.subSequence(startIndex, endIndex)

        override fun toString() = inner.toString()
    }
}
