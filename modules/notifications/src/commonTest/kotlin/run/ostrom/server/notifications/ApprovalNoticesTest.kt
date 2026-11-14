// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import run.ostrom.server.notifications.domain.ApprovalNotices
import run.ostrom.server.notifications.domain.MAX_NOTICE_TEXT
import run.ostrom.server.notifications.domain.PUSH_BODY
import run.ostrom.server.notifications.domain.PUSH_TITLE
import run.ostrom.server.notifications.domain.PushDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** What an approver is told of a request for a yes, on a phone and by e-mail. */
class ApprovalNoticesTest {
    private val notices = ApprovalNotices("https://console.ostrom.test")
    private val org = Uuid.random()

    private fun phone(token: String) = PushDevice(
        Uuid.random(), org, Uuid.random(), token, "fcm", "ios", null, Instant.DISTANT_PAST, Instant.DISTANT_PAST,
    )

    @Test
    fun thePushNamesWhoAsksWhatAndWhereAndOpensTheApproversRequest() {
        val gate = sampleGate(askedBy = listOf("ana", "eli"))
        val request = Uuid.random()

        val push = notices.push(request, phone("fcm-token-bob-phone-1"))

        assertEquals("fcm-token-bob-phone-1", push.to)
        assertEquals(PUSH_TITLE, push.title)
        assertEquals(PUSH_BODY, push.body)
        val shown = push.title + push.body
        listOf("ana", "eli", "Push to main", "acme/app").forEach {
            assertFalse(shown.contains(it), "$it on a lock screen")
        }
        assertEquals(mapOf("kind" to "approval", "approvalId" to request.toString()), push.data)
    }

    @Test
    fun aGateWithoutRepositoryOrAskerStillReads() {
        val gate = sampleGate(askedBy = emptyList(), repo = null)

        assertEquals("Someone asks for your yes", ApprovalNotices.title(gate))
        assertEquals("Push to main", ApprovalNotices.body(gate))
    }

    @Test
    fun aLongLabelIsCutToTwoLines() {
        val gate = sampleGate(label = "x".repeat(200), repo = null)

        assertEquals("x".repeat(MAX_NOTICE_TEXT) + "…", ApprovalNotices.body(gate))
        assertEquals(120, MAX_NOTICE_TEXT)
    }

    @Test
    fun aLabelWithLineBreaksReadsOnOneLineAndACutNeverSplitsASurrogatePair() {
        val broken = sampleGate(label = "Push\nto\tmain", repo = null)
        assertEquals("Push to main", ApprovalNotices.body(broken))

        // 119 letters then a character outside the BMP (two UTF-16 units) straddling the cut at 120.
        val pair = sampleGate(label = "x".repeat(MAX_NOTICE_TEXT - 1) + "\uD83D\uDE00tail", repo = null)
        assertEquals("x".repeat(MAX_NOTICE_TEXT - 1) + "…", ApprovalNotices.body(pair))
    }

    @Test
    fun theEmailOfAGateWithNeitherLabelNorRepositoryHasNoDanglingColon() {
        val gate = sampleGate(label = "", repo = null)

        val mail = notices.email(gate, Uuid.random(), "bob@example.com")

        assertEquals("ana asks for your yes", mail.subject)
        assertTrue(mail.text.startsWith("ana asks for your yes.\n\nOpen the request"), mail.text)
    }

    @Test
    fun theEmailLinksToTheRequestInTheConsoleAndEscapesWhatTheGateSays() {
        val gate = sampleGate(label = "Deploy <prod> & \"main\" 'now'")
        val request = Uuid.random()

        val mail = notices.email(gate, request, "bob@example.com")

        val link = "https://console.ostrom.test/yes/$request"
        assertEquals("bob@example.com", mail.to)
        assertEquals("ana asks for your yes: Deploy <prod> & \"main\" 'now' · acme/app", mail.subject)
        assertTrue(mail.text.contains("Open the request: $link"), mail.text)
        assertTrue(mail.html.contains("href=\"$link\""), mail.html)
        assertTrue(mail.html.contains("Deploy &lt;prod&gt; &amp; &quot;main&quot; &#39;now&#39;"), mail.html)
        assertFalse(mail.html.contains("<prod>"), "Nothing the asker wrote becomes markup")
        assertTrue(mail.text.contains("Nothing is approved from this e-mail."))
    }
}
