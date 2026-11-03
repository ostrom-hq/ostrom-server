// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler

import dev.ostrom.server.compiler.domain.models.PackageAudit
import dev.ostrom.server.compiler.presentation.toAuditEvent
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** An audit line answers its meta as the contract's `AuditEvent` says: every value a string. */
class AuditEventMapperTest {
    @Test
    fun countsAreStringsAndAnUnknownValueIsLeftOut() {
        val meta = JsonObject(
            mapOf(
                "files" to JsonPrimitive(5),
                "pinned" to JsonPrimitive(true),
                "reason" to JsonPrimitive("leaked"),
                "repo" to JsonNull,
            ),
        )
        val line = PackageAudit("a1", "2026-11-03T12:00:00Z", "package.compiled", "user:a", "1.0.0", meta)

        val event = line.toAuditEvent()

        assertEquals(mapOf("files" to "5", "pinned" to "true", "reason" to "leaked"), event.meta)
        val rest = listOf(event.id, event.at, event.event, event.actor, event.subject)
        assertEquals(listOf("a1", "2026-11-03T12:00:00Z", "package.compiled", "user:a", "1.0.0"), rest)
    }
}
