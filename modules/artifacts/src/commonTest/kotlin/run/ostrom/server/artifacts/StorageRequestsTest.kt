// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.storage.ArtifactsInactiveException
import run.ostrom.server.artifacts.domain.storage.InactiveReason
import run.ostrom.server.artifacts.presentation.SaveArtifactStorageRequest
import run.ostrom.server.artifacts.presentation.toInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TC-ART-4: a bucket as the dashboard sends it, and the refusal while artifacts are off. */
class StorageRequestsTest {
    private val request = SaveArtifactStorageRequest(0, " https://s3.example.com ", " auto ", " b ", accessKeyId = " k ")

    @Test
    fun aRequestIsTrimmedAndABlankSecretKeepsTheStoredOne() {
        val kept = request.toInput()
        assertEquals("https://s3.example.com" to "b", kept.endpoint to kept.bucket)
        assertNull(kept.secretAccessKey)
        assertNull(request.copy(secretAccessKey = "  ").toInput().secretAccessKey)
        assertEquals("s", request.copy(secretAccessKey = " s ").toInput().secretAccessKey)
    }

    @Test
    fun eachInactiveReasonIsItsOwnRefusal() {
        for (reason in InactiveReason.entries) {
            val refusal = ArtifactsInactiveException(reason)
            assertEquals(reason.wire, refusal.type)
            assertTrue(refusal.message.startsWith("Artifacts are off"))
        }
    }
}
