// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.secrets

import dev.ostrom.server.secrets.domain.SecretScrubber
import dev.ostrom.server.secrets.domain.SecretValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SecretScrubberTest {
    @Test
    fun everyValueIsRemovedTheLongestFirst() {
        val text = "token=abcdefgh123 and abcdefgh and abcdefgh123"

        assertEquals(
            "token=[REDACTED] and [REDACTED] and [REDACTED]",
            SecretScrubber.scrub(text, listOf("abcdefgh", "abcdefgh123")),
        )
    }

    @Test
    fun shortValuesAreLeftAlone() {
        assertEquals("a short pw", SecretScrubber.scrub("a short pw", listOf("short")))
        assertEquals("nothing to hide", SecretScrubber.scrub("nothing to hide", listOf("absent-value")))
    }

    @Test
    fun aSecretValueNeverPrintsItself() {
        val value = SecretValue("p4ssw0rd!")

        assertEquals("SecretValue([REDACTED])", value.toString())
        assertEquals(SecretValue("p4ssw0rd!"), value)
        assertEquals(SecretValue("p4ssw0rd!").hashCode(), value.hashCode())
        assertNotEquals<Any>(value, "p4ssw0rd!")
    }
}
