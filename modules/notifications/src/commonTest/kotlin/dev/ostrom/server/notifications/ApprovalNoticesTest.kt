// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications

import dev.ostrom.server.notifications.domain.ApprovalNotices
import dev.ostrom.server.notifications.domain.MAX_NOTICE_TEXT
import dev.ostrom.server.notifications.domain.PUSH_BODY
import dev.ostrom.server.notifications.domain.PUSH_TITLE
import dev.ostrom.server.notifications.domain.PushDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ApprovalNoticesTest {
    private val notices = ApprovalNotices("https://console.ostrom.test")
    private val org = Uuid.random()

    private fun phone(token: String) = PushDevice(
        Uuid.random(), org, Uuid.random(), token, "expo", "ios", null, Instant.DISTANT_PAST, Instant.DISTANT_PAST,
    )

    @Test
    fun thePushNamesWhoAsksWhatAndWhereAndOpensTheApproversRequest() {
        val gate = sampleGate(org, askedBy = listOf("ana", "eli"))
        val request = sampleRequest(gate, "bob")

        val push = notices.push(request, phone("ExponentPushToken[bob-phone-1]"))

        assertEquals("ExponentPushToken[bob-phone-1]", push.to)
        assertEquals(PUSH_TITLE, push.title)
        assertEquals(PUSH_BODY, push.body)
        val shown = push.title + push.body
        listOf("ana", "eli", "Push to main", "acme/app").forEach { assertFalse(shown.contains(it), "$it on a lock screen") }
        assertEquals(mapOf("kind" to "approval", "approvalId" to request.id.toString()), push.data)
    }

    @Test
    fun aGateWithoutRepositoryOrAskerStillReads() {
        val gate = sampleGate(org, askedBy = emptyList(), repo = null)

        assertEquals("Someone asks for your yes", notices.title(gate))
        assertEquals("Push to main", notices.body(gate))
    }

    @Test
    fun aLongLabelIsCutToTwoLines() {
        val gate = sampleGate(org, label = "x".repeat(200), repo = null)

        assertEquals("x".repeat(MAX_NOTICE_TEXT) + "…", notices.body(gate))
        assertEquals(120, MAX_NOTICE_TEXT)
    }

    @Test
    fun theEmailLinksToTheRequestInTheConsoleAndEscapesWhatTheGateSays() {
        val gate = sampleGate(org, label = "Deploy <prod> & \"main\" 'now'")
        val request = sampleRequest(gate, "bob")

        val mail = notices.email(gate, request, "bob@example.com")

        val link = "https://console.ostrom.test/yes/${request.id}"
        assertEquals("bob@example.com", mail.to)
        assertEquals("ana asks for your yes: Deploy <prod> & \"main\" 'now' · acme/app", mail.subject)
        assertTrue(mail.text.contains("Open the request: $link"), mail.text)
        assertTrue(mail.html.contains("href=\"$link\""), mail.html)
        assertTrue(mail.html.contains("Deploy &lt;prod&gt; &amp; &quot;main&quot; &#39;now&#39;"), mail.html)
        assertFalse(mail.html.contains("<prod>"), "Nothing the asker wrote becomes markup")
        assertTrue(mail.text.contains("Nothing is approved from this e-mail."))
    }
}
