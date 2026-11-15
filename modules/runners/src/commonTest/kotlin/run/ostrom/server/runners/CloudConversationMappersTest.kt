// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.CloudEvent
import run.ostrom.server.runners.domain.CloudEventType
import run.ostrom.server.runners.domain.CloudInput
import run.ostrom.server.runners.domain.CloudInputKind
import run.ostrom.server.runners.domain.CloudInputStatus
import run.ostrom.server.runners.domain.CloudSubagent
import run.ostrom.server.runners.domain.PermissionAnswer
import run.ostrom.server.runners.domain.QuestionAnswer
import run.ostrom.server.runners.domain.storedObject
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.presentation.answerText
import run.ostrom.server.runners.presentation.toDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** What a stored row reads as on the wire. */
class CloudConversationMappersTest {
    private val t0 = Instant.parse("2026-11-15T10:00:00Z")

    @Test
    fun storedTextIsAnObjectOrNothing() {
        assertNull(storedObject(null))
        assertNull(storedObject("{cut"))
        assertNull(storedObject("[1]"))
        assertEquals(JsonObject(mapOf("a" to JsonPrimitive(1))), storedObject("""{"a":1}"""))
    }

    @Test
    fun anEventWhoseDataIsNotAnObjectReadsAsAnEmptyOne() {
        fun event(data: String) = CloudEvent(Uuid.random(), 1, CloudEventType.STATUS, t0, 0, data).toDto()
        assertEquals(JsonObject(emptyMap()), event("{cut").data)
        assertEquals(JsonObject(emptyMap()), event("[1]").data)
        assertEquals(JsonPrimitive("x"), event("""{"a":"x"}""").data["a"])
    }

    @Test
    fun aSubagentAndAnInputMapEveryTime() {
        fun subagent(endedAt: Instant?) =
            CloudSubagent("a1", "Explore", "Look", "done", t0, endedAt, 3, null, "t1").toDto()
        assertEquals("2026-11-15T10:00:00Z", subagent(t0).endedAt)
        assertNull(subagent(null).endedAt)
        val input = CloudInput(
            Uuid.random(), ORG_A, Uuid.random(), CloudInputKind.MESSAGE, CloudInputStatus.CONSUMED, "alice", null,
            "Hi", true, "k1", null, t0, deliveredAt = t0, deliveredEpoch = 2, consumedAt = t0, finishedAt = t0,
        ).toDto()
        assertEquals("2026-11-15T10:00:00Z", input.deliveredAt)
        assertEquals("2026-11-15T10:00:00Z", input.consumedAt)
        assertEquals("consumed", input.status)
    }

    @Test
    fun anAnswerReadsAsTheRunnerExpectsIt() {
        assertEquals(
            """{"requestId":"r1","behavior":"deny","message":"Not now."}""",
            answerText(PermissionAnswer("r1", false, "Not now.", emptyList())),
        )
        val allow = PermissionAnswer("r1", true, null, emptyList())
        assertEquals("""{"requestId":"r1","behavior":"allow"}""", answerText(allow))
        assertEquals(
            """{"requestId":"q1","behavior":"allow","answers":[{"question":0,"options":[1],"text":"Blue"}]}""",
            answerText(PermissionAnswer("q1", true, null, listOf(QuestionAnswer(0, listOf(1), "Blue")))),
        )
    }

    @Test
    fun anUnknownStoredNameReadsAsTheDefault() {
        assertEquals(CloudInputStatus.QUEUED, CloudInputStatus.parse("nope"))
        assertEquals(CloudInputKind.MESSAGE, CloudInputKind.parse("nope"))
    }
}
