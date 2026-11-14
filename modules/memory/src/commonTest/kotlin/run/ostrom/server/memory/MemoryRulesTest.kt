// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory

import run.ostrom.server.memory.domain.InvalidMemoryException
import run.ostrom.server.memory.domain.MemoryRules
import run.ostrom.server.memory.domain.MemoryScope
import run.ostrom.server.memory.domain.MemorySecretException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class MemoryRulesTest {
    private val now = Instant.parse("2026-11-14T12:00:00Z")

    @Suppress("LongParameterList") // The entry's six fields, each defaulting to a valid one.
    private fun valid(
        title: String = "Deploy order",
        summary: String = "Core first, then the server",
        content: String = "Merge core, repin, then merge the server.",
        tags: List<String> = listOf("deploy", "core.v1"),
        refs: List<String> = listOf("https://github.com/ostrom-hq/ostrom-core/pull/192"),
        expiresAt: Instant? = now + 1.hours,
    ) = MemoryRules.validate(title, summary, content, tags, refs, expiresAt, now)

    @Test
    fun anEntryWithinItsBoundsIsAccepted() {
        valid()
        valid(tags = emptyList(), refs = emptyList(), expiresAt = null)
        valid(title = "t".repeat(160), summary = "s".repeat(300), content = "c".repeat(16_000))
    }

    @Test
    fun aTitleIsOneLineOfAtMost160Characters() {
        for (title in listOf("", "  ", "t".repeat(161), "two\nlines")) {
            assertFailsWith<InvalidMemoryException> { valid(title = title) }
        }
    }

    @Test
    fun aSummaryIsOneLineOfAtMost300Characters() {
        for (summary in listOf("", "s".repeat(301), "a\tb")) {
            assertFailsWith<InvalidMemoryException> { valid(summary = summary) }
        }
    }

    @Test
    fun contentHasAtMost16000CharactersAndNoNul() {
        for (content in listOf(" ", "c".repeat(16_001), "a\u0000b")) {
            assertFailsWith<InvalidMemoryException> { valid(content = content) }
        }
        valid(content = "many\nlines\tare fine")
    }

    @Test
    fun tagsAreAtMostEightDistinctLowercaseWords() {
        val refused = listOf(
            List(9) { "t$it" },
            listOf("a", "a"),
            listOf("Upper"),
            listOf("-dash"),
            listOf("x".repeat(41)),
        )
        for (tags in refused) assertFailsWith<InvalidMemoryException> { valid(tags = tags) }
        valid(tags = List(8) { "t$it" } + emptyList())
        valid(tags = listOf("x".repeat(40), "a_b-c.d"))
    }

    @Test
    fun referencesAreAtMostTenOneLineTexts() {
        for (refs in listOf(List(11) { "r$it" }, listOf(""), listOf("r".repeat(301)), listOf("a\nb"))) {
            assertFailsWith<InvalidMemoryException> { valid(refs = refs) }
        }
    }

    @Test
    fun anExpiryIsInTheFuture() {
        assertFailsWith<InvalidMemoryException> { valid(expiresAt = now) }
        assertFailsWith<InvalidMemoryException> { valid(expiresAt = now - 1.hours) }
    }

    @Test
    fun textThatLooksLikeACredentialIsRefused() {
        val secrets = listOf(
            "-----BEGIN RSA PRIVATE KEY-----",
            "key sk-ant-api03-" + "a".repeat(30),
            "ghp_" + "a".repeat(36),
            "github_pat_" + "a".repeat(30),
            "xoxb-1234567890-abc",
            "AKIAABCDEFGHIJKLMNOP",
            "osp_" + "a".repeat(20),
            "opk_" + "a".repeat(20),
            "Authorization: Bearer abcdefghijklmnopqrstuvwxyz",
            "password = hunter2hunter2!",
        )
        for (secret in secrets) {
            assertFailsWith<MemorySecretException>(secret) { valid(content = "see $secret") }
        }
        assertFailsWith<MemorySecretException> { valid(title = "AKIAABCDEFGHIJKLMNOP") }
        assertFailsWith<MemorySecretException> { valid(summary = "api_key: abcdefghijklmnop") }
        assertFailsWith<MemorySecretException> { valid(refs = listOf("ghp_" + "b".repeat(30))) }
        valid(content = "The deploy key lives in the vault under ostrom/deploy; ask an administrator.")
    }

    @Test
    fun aFilterTagIsATag() {
        MemoryRules.validateTag("deploy")
        assertFailsWith<InvalidMemoryException> { MemoryRules.validateTag("Deploy!") }
    }

    @Test
    fun aSearchIsAtMostFiveDistinctWordsOfTwoCharactersOrMore() {
        assertEquals(listOf("deploy", "core.v1"), MemoryRules.terms("Deploy, a core.v1 DEPLOY"))
        assertEquals(5, MemoryRules.terms("one two three four five six").size)
        assertFailsWith<InvalidMemoryException> { MemoryRules.terms("a ! ?") }
        assertEquals(listOf("core"), MemoryRules.terms("core ${"x".repeat(41)}"), "a word past 40 characters is left")
    }

    @Test
    fun aScopeIsTeamOrPersonal() {
        assertEquals(MemoryScope.TEAM, MemoryScope.parse("team"))
        assertEquals(MemoryScope.PERSONAL, MemoryScope.parse("personal"))
        assertFailsWith<InvalidMemoryException> { MemoryScope.parse("repo") }
    }
}
