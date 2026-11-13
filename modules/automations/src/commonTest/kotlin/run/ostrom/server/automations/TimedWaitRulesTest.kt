// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.automations

import run.ostrom.server.automations.domain.InvalidWaitException
import run.ostrom.server.automations.domain.TimedWaitRules
import run.ostrom.server.automations.domain.WaitAction
import run.ostrom.server.automations.domain.WaitEvent
import run.ostrom.server.automations.domain.WaitField
import run.ostrom.server.automations.domain.WaitFieldType
import run.ostrom.server.automations.domain.WaitResolver
import run.ostrom.server.automations.domain.WaitSchedule
import run.ostrom.server.automations.domain.WaitSpec
import run.ostrom.server.automations.domain.WaitTimer
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The rules of the waits that resolve by themselves (TeamCode's `WaitRules`, `docs/waits.md`). */
class TimedWaitRulesTest {
    private val now = Instant.parse("2026-11-13T10:00:00Z")
    private val spec = WaitSpec("CI of #42")

    private fun time(schedule: WaitSchedule, question: WaitSpec = spec) =
        TimedWaitRules.timer(WaitResolver.TIME, question, schedule, now, null)

    private fun event(schedule: WaitSchedule = WaitSchedule()) =
        TimedWaitRules.timer(WaitResolver.EVENT, spec, schedule, now, "hash")

    private fun invalid(message: String, block: () -> Unit) =
        assertEquals(message, assertFailsWith<InvalidWaitException> { block() }.message)

    @Test
    fun aResolverIsHumanByDefaultAndOnlyOneOfTheThree() {
        assertEquals(WaitResolver.HUMAN, TimedWaitRules.resolver(null))
        assertEquals(WaitResolver.TIME, TimedWaitRules.resolver("time"))
        assertEquals(WaitResolver.EVENT, TimedWaitRules.resolver("event"))
        invalid("resolver is human, time or event.") { TimedWaitRules.resolver("all") }
    }

    @Test
    fun aTimeResolvesAfterItsSecondsOrAtItsInstant() {
        assertEquals(WaitTimer(WaitResolver.TIME, now + 30.seconds), time(WaitSchedule(after = 30)))
        assertEquals(now + 30.days, time(WaitSchedule(after = 30L * 24 * 3600)).at)
        val at = time(WaitSchedule(at = "2026-11-14T09:00:00.123456Z", resumeMessage = "Check the deploy"))
        assertEquals(Instant.parse("2026-11-14T09:00:00.123Z"), at.at, "To the millisecond, as stored")
        assertEquals(at.at, at.dueAt)
        assertNull(at.timeoutAt)
        assertEquals("Check the deploy", at.resumeMessage)
    }

    @Test
    fun aTimeIsGivenExactlyOneInstantWithinThirtyDays() {
        invalid("Give either at or after, not both nor none.") { time(WaitSchedule()) }
        invalid("Give either at or after, not both nor none.") {
            time(WaitSchedule(at = "2026-11-14T09:00:00Z", after = 60))
        }
        invalid("after is 30 seconds to 30 days (2592000 seconds).") { time(WaitSchedule(after = 29)) }
        invalid("after is 30 seconds to 30 days (2592000 seconds).") { time(WaitSchedule(after = 2_592_001)) }
        invalid("at is an ISO-8601 instant in UTC, such as 2026-11-02T09:00:00Z.") { time(WaitSchedule(at = "soon")) }
        invalid("at must be in the future.") { time(WaitSchedule(at = "2026-11-13T10:00:00Z")) }
        invalid("at is at most 30 days from now.") { time(WaitSchedule(at = "2026-12-13T10:00:01Z")) }
        invalid("source and timeoutSeconds belong to waits for an event.") {
            time(WaitSchedule(after = 60, source = "webhook"))
        }
        invalid("source and timeoutSeconds belong to waits for an event.") {
            time(WaitSchedule(after = 60, timeoutSeconds = 60))
        }
    }

    @Test
    fun anEventTimesOutAfterADayByDefaultAndAWeekAtMost() {
        assertEquals(WaitTimer(WaitResolver.EVENT, now + 1.days, "webhook", null, "hash"), event())
        val week = event(WaitSchedule(source = "webhook", timeoutSeconds = 604_800))
        assertEquals(now + 7.days, week.timeoutAt)
        assertNull(week.dueAt)
        invalid("timeoutSeconds is 30 seconds to 7 days (604800 seconds).") {
            event(WaitSchedule(timeoutSeconds = 29))
        }
        invalid("timeoutSeconds is 30 seconds to 7 days (604800 seconds).") {
            event(WaitSchedule(timeoutSeconds = 604_801))
        }
        invalid("source is webhook.") { event(WaitSchedule(source = "github")) }
        invalid("at and after belong to waits for a time.") { event(WaitSchedule(after = 60)) }
        invalid("at and after belong to waits for a time.") { event(WaitSchedule(at = "2026-11-14T09:00:00Z")) }
    }

    @Test
    fun aWaitThatResolvesByItselfAsksNobody() {
        val field = WaitField("ok", WaitFieldType.CONFIRM, "OK?")
        val message = "A wait for a time or an event asks nobody: it has no fields and no actions."
        invalid(message) { time(WaitSchedule(after = 60), spec.copy(fields = listOf(field))) }
        invalid(message) { time(WaitSchedule(after = 60), spec.copy(actions = listOf(WaitAction("go", "Go")))) }
        invalid("resumeMessage is 1 to 2000 characters.") { time(WaitSchedule(after = 60, resumeMessage = " ")) }
        invalid("resumeMessage is 1 to 2000 characters.") {
            time(WaitSchedule(after = 60, resumeMessage = "x".repeat(2_001)))
        }
        invalid("resumeMessage is 1 to 2000 characters.") { time(WaitSchedule(after = 60, resumeMessage = "a\u0007")) }
        assertEquals("a\n\tb", time(WaitSchedule(after = 60, resumeMessage = "a\n\tb")).resumeMessage)
    }

    @Test
    fun aScheduleThatSaysNothingIsEmpty() {
        assertTrue(WaitSchedule().empty)
        assertFalse(WaitSchedule(after = 60).empty)
    }

    @Test
    fun anEmptyBodyIsASuccessAndAnEventCarriesItsResultUrlAndNote() {
        assertEquals(WaitEvent("success", null, null), TimedWaitRules.event(ByteArray(0)))
        assertEquals(WaitEvent("success", null, null), TimedWaitRules.event(" \n".encodeToByteArray()))
        assertEquals(WaitEvent("success", null, null), TimedWaitRules.event("""{"url": null}""".encodeToByteArray()))
        val event = TimedWaitRules.event(
            """{"result":"failure","url":"https://ci.example/run/1","note":"3 tests\nfailed","extra":1}"""
                .encodeToByteArray(),
        )
        assertEquals(WaitEvent("failure", "https://ci.example/run/1", "3 tests\nfailed"), event)
        assertEquals(
            mapOf(
                "result" to JsonPrimitive("failure"),
                "url" to JsonPrimitive("https://ci.example/run/1"),
                "note" to JsonPrimitive("3 tests\nfailed"),
            ),
            event.values,
        )
        assertEquals(mapOf("result" to JsonPrimitive("success")), WaitEvent("success", null, null).values)
    }

    @Test
    fun anEventThatIsNotOneIsRefusedWithWhatToFix() {
        fun bad(message: String, body: String) = invalid(message) { TimedWaitRules.event(body.encodeToByteArray()) }
        val notAnObject = "Post a JSON object such as {\"result\": \"success\"}."
        bad(notAnObject, "not json")
        bad(notAnObject, "[1]")
        bad("result is a text.", """{"result": 1}""")
        bad("url is a text.", """{"url": {"a": 1}}""")
        val result = "result is 1 to 64 characters, such as success or failure."
        bad(result, """{"result": " "}""")
        bad(result, """{"result": "${"x".repeat(65)}"}""")
        bad(result, """{"result": "a\u0007"}""")
        val url = "url is an https address without spaces."
        bad(url, """{"url": "http://ci.example"}""")
        bad(url, """{"url": "https://ci.example/a b"}""")
        bad(url, """{"url": "https://${"x".repeat(2_000)}"}""")
        val note = "note is at most 2000 characters."
        bad(note, """{"note": "${"x".repeat(2_001)}"}""")
        bad(note, """{"note": "a\u0007"}""")
    }
}
