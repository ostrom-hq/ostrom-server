// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage

import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsValues
import run.ostrom.server.usage.domain.InvalidUsageEventException
import run.ostrom.server.usage.domain.InvalidUsageQueryException
import run.ostrom.server.usage.domain.UsageCursor
import run.ostrom.server.usage.domain.UsageGroupBy
import run.ostrom.server.usage.domain.UsageKind
import run.ostrom.server.usage.domain.UsageReport
import run.ostrom.server.usage.domain.UsageTotal
import run.ostrom.server.usage.presentation.UsageEventRequest
import run.ostrom.server.usage.presentation.toClientEvent
import run.ostrom.server.usage.presentation.toCursor
import run.ostrom.server.usage.presentation.toGroupBy
import run.ostrom.server.usage.presentation.toResponse
import run.ostrom.server.usage.presentation.toUsageCursor
import run.ostrom.server.usage.presentation.writeEventStream
import run.ostrom.server.usage.domain.UsageSettings
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class UsagePresentationTest {
    @Test
    fun aDeclaredEventMapsItsKindInAnyCaseAndItsTime() {
        val event = UsageEventRequest("Skill", "deploy", "s-1", 12, "ok", "2026-10-30T10:00:00Z").toClientEvent()

        assertEquals(UsageKind.SKILL, event.kind)
        assertEquals(Instant.parse("2026-10-30T10:00:00Z"), event.at)
        assertEquals(null, UsageEventRequest("hook", "Stop").toClientEvent().at)
    }

    @Test
    fun anUnknownKindOrTimeIsRefused() {
        assertFailsWith<InvalidUsageEventException> { UsageEventRequest("payment", "x").toClientEvent() }
        assertFailsWith<InvalidUsageEventException> {
            UsageEventRequest(
                "hook",
                "Stop",
                at = "yesterday",
            ).toClientEvent()
        }
    }

    @Test
    fun theGroupingIsParsedInAnyCase() {
        assertEquals(UsageGroupBy.PROVIDER, "Provider".toGroupBy())
        assertEquals(UsageGroupBy.ACCOUNT, "account".toGroupBy())
        assertFailsWith<InvalidUsageQueryException> { "team".toGroupBy() }
    }

    @Test
    fun aCursorIsTheTimeAndTheIdOrABareTime() {
        val event = modelEvent(1)
        assertEquals(UsageCursor(event.at, event.id), event.toCursor().toUsageCursor())
        assertEquals(UsageCursor(Instant.parse("2026-10-01T00:00:00Z"), null), "2026-10-01T00:00:00Z".toUsageCursor())
        assertFailsWith<InvalidUsageQueryException> { "soon".toUsageCursor() }
        assertFailsWith<InvalidUsageQueryException> { "2026-10-01T00:00:00Z~not-an-id".toUsageCursor() }
    }

    @Test
    fun anEventMapsToTheApiWithLowerCaseKind() {
        val account = Uuid.random()
        val response = modelEvent(42).copy(accountId = account, sessionId = "s-9").toResponse()

        assertEquals("model", response.kind)
        assertEquals(account.toString(), response.accountId)
        assertEquals(TestCallers.adminA.memberId.toString(), response.memberId)
        assertEquals("s-9", response.sessionId)
        assertEquals(null, response.launchId)
        assertEquals("lch_1", modelEvent(42).copy(launchId = "lch_1").toResponse().launchId)
        assertEquals(42L, response.costMicros)
        assertEquals("2026-10-30T12:00:00Z", response.at)
        val orgLevel = modelEvent(1, memberId = null).toResponse()
        assertEquals(null, orgLevel.memberId)
        assertEquals(null, orgLevel.accountId)
    }

    @Test
    fun aReportMapsUnattributedMembersAndUnnamedModels() {
        val total = UsageTotal(null, 2, 10, 5, 0, 0, 1_500_000)
        val at = Instant.parse("2026-10-01T00:00:00Z")
        val response = UsageReport(at, at + 1.days, total, listOf(total), listOf(total)).toResponse()

        assertEquals(mapOf("unattributed" to 1.5), response.byMember)
        assertEquals(mapOf("" to 1.5), response.byModel)
        assertEquals("2026-10-02T00:00:00Z", response.to)
    }

    @Test
    fun theRetentionDefaultsToFourHundredDaysAndIsBounded() = runTest {
        fun stored(days: String?) = InstanceSettings { section ->
            assertEquals("usage", section)
            SettingsValues(listOfNotNull(days?.let { "retentionDays" to it }).toMap())
        }
        assertEquals(400.days, UsageSettings.retention(stored(null)))
        assertEquals(30.days, UsageSettings.retention(stored("30")))
        assertEquals(3650.days, UsageSettings.retention(stored("3650")))
        for (invalid in listOf("0", "3651", "forever")) {
            assertEquals(400.days, UsageSettings.retention(stored(invalid)), "Never stored, read as the default")
            assertEquals("The retention is a whole number of days, 1 to 3650.", retentionField().check(invalid))
        }
        assertEquals(null, retentionField().check("1"))
        assertEquals("OSTROM_USAGE_RETENTION_DAYS", retentionField().legacyVariable)
        assertEquals(false, retentionField().secret)
    }

    private fun retentionField() = checkNotNull(UsageSettings.SECTION.field("retentionDays"))

    @Test
    fun theStreamAnnouncesTheSubscriptionThenSendsEventsAndHeartbeats() = runTest {
        val channel = ByteChannel(autoFlush = true)
        val items = MutableSharedFlow<String>()
        val writer = launch {
            channel.writeEventStream(15.seconds, 10.minutes) { onSubscribed -> items.onSubscription { onSubscribed() } }
        }

        assertEquals(": connected", channel.readUTF8Line())
        assertEquals("", channel.readUTF8Line())
        items.emit("""{"costMicros":1}""")
        assertEquals("""data: {"costMicros":1}""", channel.readUTF8Line())
        assertEquals("", channel.readUTF8Line())
        // Nothing else to read: the virtual clock jumps to the next heartbeat.
        assertEquals(": heartbeat", channel.readUTF8Line())
        assertEquals(15.seconds.inWholeMilliseconds, testScheduler.currentTime)
        writer.cancel()
    }

    @Test
    fun theStreamEndsAfterItsLifetimeSoThatTheClientReconnectsAndIsCheckedAgain() = runTest {
        val channel = ByteChannel(autoFlush = true)
        channel.writeEventStream(15.seconds, 40.seconds) { onSubscribed ->
            MutableSharedFlow<String>().onSubscription { onSubscribed() }
        }
        channel.flushAndClose()

        val lines = buildList { while (true) add(channel.readUTF8Line() ?: break) }
        assertEquals(listOf(": connected", "", ": heartbeat", "", ": heartbeat", ""), lines)
        assertEquals(40.seconds.inWholeMilliseconds, testScheduler.currentTime)
    }

    @Test
    fun theStreamEndsWithItsItems() = runTest {
        val channel = ByteChannel(autoFlush = true)
        channel.writeEventStream(15.seconds, 10.minutes) { onSubscribed ->
            onSubscribed()
            flowOf("a", "b")
        }
        channel.flushAndClose()

        val lines = buildList { while (true) add(channel.readUTF8Line() ?: break) }
        assertEquals(listOf(": connected", "", "data: a", "", "data: b", ""), lines)
    }

    @Test
    fun theStreamEndsQuietlyWhenTheClientIsGone() = runTest {
        val channel = ByteChannel(autoFlush = true)
        channel.cancel(IOException("client gone"))

        channel.writeEventStream(15.seconds, 10.minutes) { onSubscribed ->
            onSubscribed()
            flowOf("a")
        }
        assertTrue(channel.isClosedForWrite)
    }
}
