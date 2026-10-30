package dev.ostrom.server.kernel

import dev.ostrom.server.kernel.logging.JsonLogger
import dev.ostrom.server.kernel.logging.LogLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class JsonLoggerTest {
    private val clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-10-30T01:00:00Z")
    }

    @Test
    fun writesOneJsonObjectPerEvent() {
        val lines = mutableListOf<String>()
        val logger = JsonLogger(LogLevel.INFO, clock, { lines += it }).with(mapOf("module" to "instance"))
        logger.error("request failed", mapOf("status" to 500, "path" to "/v1/x"), IllegalStateException("boom"))

        val event = Json.parseToJsonElement(lines.single()).jsonObject
        assertEquals("2026-10-30T01:00:00Z", event["ts"]?.jsonPrimitive?.content)
        assertEquals("ERROR", event["level"]?.jsonPrimitive?.content)
        assertEquals("request failed", event["msg"]?.jsonPrimitive?.content)
        assertEquals("instance", event["module"]?.jsonPrimitive?.content)
        assertEquals("500", event["status"]?.jsonPrimitive?.content)
        assertEquals("IllegalStateException", event["error"]?.jsonPrimitive?.content)
        assertEquals("boom", event["error_message"]?.jsonPrimitive?.content)
    }

    @Test
    fun dropsEventsBelowTheMinimumLevel() {
        val lines = mutableListOf<String>()
        val logger = JsonLogger(LogLevel.WARN, clock, { lines += it })
        logger.debug("noise")
        logger.info("noise")
        logger.warn("kept")
        assertEquals(1, lines.size)
        assertTrue("kept" in lines.single())
    }

    @Test
    fun writesEachKindOfFieldAndAnAnonymousErrorWithoutMessage() {
        val lines = mutableListOf<String>()
        val logger = JsonLogger(LogLevel.DEBUG, clock, { lines += it })
        logger.warn("odd failure", mapOf("retry" to true, "missing" to null, "count" to 3L), object : Exception() {})
        logger.info("plain")

        val event = Json.parseToJsonElement(lines.first()).jsonObject
        assertEquals("true", event["retry"]?.jsonPrimitive?.content)
        assertEquals("null", event["missing"].toString())
        assertEquals("3", event["count"]?.jsonPrimitive?.content)
        assertEquals("Throwable", event["error"]?.jsonPrimitive?.content)
        assertNull(event["error_message"])
        assertEquals(setOf("ts", "level", "msg"), Json.parseToJsonElement(lines.last()).jsonObject.keys)
    }
}
