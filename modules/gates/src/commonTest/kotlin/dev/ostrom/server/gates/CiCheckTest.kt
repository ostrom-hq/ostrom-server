// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates

import dev.ostrom.server.gates.domain.models.CiCheck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class CiCheckTest {
    @Test
    fun aCiCheckKeepsWhatTheForgeRecorded() {
        val at = Instant.parse("2026-11-02T16:00:00Z")
        val url = "https://github.com/acme/api/runs/77"
        val check = CiCheck(77, "unit tests", SHA, "success", "github-actions", url, at)
        val read = with(check) { listOf(id, name, headSha, conclusion, app, url, completedAt) }
        assertEquals(listOf(77L, "unit tests", SHA, "success", "github-actions", url, at), read)
    }
}

private const val SHA = "0123456789abcdef0123456789abcdef01234567"
