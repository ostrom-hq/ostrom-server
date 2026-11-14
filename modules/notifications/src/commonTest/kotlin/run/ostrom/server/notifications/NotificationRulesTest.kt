// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import run.ostrom.core.api.models.NotificationKinds
import run.ostrom.server.kernel.events.JournalEntry
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.kernel.events.WaitSummary
import run.ostrom.server.notifications.domain.ChannelResult
import run.ostrom.server.notifications.domain.Notification
import run.ostrom.server.notifications.domain.NotificationDraft
import run.ostrom.server.notifications.domain.NotificationKind
import run.ostrom.server.notifications.domain.NotificationRules
import run.ostrom.server.notifications.domain.NotificationSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** TeamCode's `NotificationRules`: which journal events tell whom, and what. */
class NotificationRulesTest {
    private val mia = Uuid.parse("0000000a-0000-0000-0000-0000000000b1")
    private val at = Instant.parse("2026-11-14T10:00:00Z")

    private fun created(waitId: String? = "w-1", type: OrgEventType = OrgEventType.WAIT_CREATED) = JournalEntry(
        7, type, "w-1", mia, at,
        JsonObject(waitId?.let { mapOf("waitId" to JsonPrimitive(it)) } ?: emptyMap()),
    )

    private fun wait(
        kind: String = "ask_human",
        resolver: String = "human",
        open: Boolean = true,
        assignee: String = mia.toString(),
        title: String = "Deploy to production?",
    ) = WaitSummary("w-1", assignee, "s-1", kind, resolver, open, title)

    @Test
    fun aWaitAPersonResolvesTellsItsAssigneeWithItsTitle() {
        val draft = NotificationRules.draft(NotificationSource(created(), wait()))
        val expected = NotificationDraft(
            7, mia, NotificationKind.WAITING, "Ostrom", "Deploy to production?", "s-1", "w-1", "wait:w-1",
        )
        assertEquals(expected, draft)
        val named = NotificationRules.draft(NotificationSource(created(), wait(), "x".repeat(300)))
        assertEquals("x".repeat(NotificationRules.MAX_TITLE), named?.title, "The session's name, cut")
        val blank = NotificationRules.draft(NotificationSource(created(), wait(), " "))
        assertEquals(NotificationRules.DEFAULT_TITLE, blank?.title)
        val long = NotificationRules.draft(NotificationSource(created(), wait(title = "y".repeat(500))))
        assertEquals(NotificationRules.MAX_BODY, long?.body?.length)
    }

    @Test
    fun aFailedTurnsWaitSaysSoWithoutItsQuestion() {
        val draft = NotificationRules.draft(NotificationSource(created(), wait(kind = "turn_failed")))
        assertEquals(NotificationKind.TURN_FAILED, draft?.kind)
        assertEquals(NotificationRules.TURN_FAILED_BODY, draft?.body)
    }

    @Test
    fun nobodyIsToldOfAWaitThatAsksNoPersonOrIsNoLongerOpen() {
        assertNull(NotificationRules.draft(NotificationSource(created(), null)), "The wait is gone")
        assertNull(NotificationRules.draft(NotificationSource(created(), wait(open = false))), "Answered already")
        assertNull(NotificationRules.draft(NotificationSource(created(), wait(resolver = "time"))), "A timer's")
        assertNull(NotificationRules.draft(NotificationSource(created(), wait(assignee = "agent:x"))), "No member")
        val todo = created(type = OrgEventType.TODO_UPDATED)
        assertNull(NotificationRules.draft(NotificationSource(todo, wait())), "Not a source type")
        assertEquals(setOf(OrgEventType.WAIT_CREATED), NotificationRules.SOURCE_TYPES)
    }

    @Test
    fun theWaitAnEventNamesIsItsWaitIdElseItsSubject() {
        assertEquals("w-9", NotificationRules.waitIdOf(created("w-9")))
        assertEquals("w-1", NotificationRules.waitIdOf(created(null)))
        val odd = created(null).copy(data = JsonObject(mapOf("waitId" to JsonObject(emptyMap()))))
        assertEquals("w-1", NotificationRules.waitIdOf(odd), "A waitId that is no string")
    }

    @Test
    fun theKindsAreTheContractsAndAChannelAnswersOneOfThreeOutcomes() {
        assertEquals(NotificationKinds.ALL, NotificationKind.entries.map { it.wire })
        assertEquals(NotificationKind.SESSION_FAILED, NotificationKind.parse("session_failed"))
        assertNull(NotificationKind.parse("approval"))
        assertEquals(ChannelResult(ChannelResult.Outcome.DELIVERED), ChannelResult.DELIVERED)
        assertEquals(ChannelResult(ChannelResult.Outcome.RETRY, "timeout"), ChannelResult.retry("timeout"))
        assertEquals(ChannelResult(ChannelResult.Outcome.FAILED, "push_off"), ChannelResult.failed("push_off"))
        val org = Uuid.random()
        val one = Notification(Uuid.random(), org, mia, NotificationKind.WAITING, "Ostrom", "Hi", null, "w-1",
            "wait:w-1", at, 7)
        assertEquals(org, one.copy().orgId)
    }
}
