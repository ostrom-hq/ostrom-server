// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sessions

import run.ostrom.server.kernel.workflows.SessionRunIds
import run.ostrom.server.sessions.domain.models.Session
import run.ostrom.server.sessions.domain.services.SessionRunLinks
import run.ostrom.server.sessions.fakes.ORG
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The workflow run of a segment, through the cloud session its launch runs in. */
class SessionRunLinksTest {
    private val runs = FakeSessionRunIds()

    private fun segment(id: String, launch: String?) = Session(
        ORG, id, id, 1, launchId = launch, agent = "claude-code", dev = "lyes", startedAt = Instant.DISTANT_PAST,
    )

    @Test
    fun aSegmentNamesTheRunOfItsLaunchAndAPageAsksOnce() = runTest {
        runs.runs["lch-run"] = "wfr_1"
        val sessions = listOf(
            segment("s-1", "lch-run"),
            segment("s-2", "lch-run"),
            segment("s-3", "lch-own"),
            segment("s-4", null),
        )

        val found = SessionRunLinks(runs).of(ORG, sessions)

        assertEquals(mapOf("s-1" to "wfr_1", "s-2" to "wfr_1"), found)
        assertEquals(listOf(setOf("lch-run", "lch-own")), runs.asked)
    }

    @Test
    fun withoutTheWorkflowRunsModuleNoSegmentHasARun() = runTest {
        assertEquals(emptyMap(), SessionRunLinks(null).of(ORG, listOf(segment("s-1", "lch-run"))))
    }

    /** The runs of the launches, and each batch asked. */
    private class FakeSessionRunIds : SessionRunIds {
        val runs = mutableMapOf<String, String>()
        val asked = mutableListOf<Set<String>>()

        override suspend fun runsOf(orgId: Uuid, sessionIds: Collection<Uuid>): Map<Uuid, String> =
            error("Not asked by the segments")

        override suspend fun runsOfLaunches(orgId: Uuid, launchIds: Collection<String>): Map<String, String> {
            asked += launchIds.toSet()
            return runs.filterKeys { it in launchIds }
        }
    }
}
