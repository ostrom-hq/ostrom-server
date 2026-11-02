// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns

import dev.ostrom.server.launches.domain.models.Launch
import dev.ostrom.server.launches.domain.models.LaunchLimit
import dev.ostrom.server.launches.domain.repositories.LaunchesRepository
import dev.ostrom.server.workflowruns.fakes.ORG
import dev.ostrom.server.workflowruns.fakes.STARTED
import dev.ostrom.server.workflowruns.infrastructure.LaunchesRunLaunches
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

class LaunchesRunLaunchesTest {
    private val cloud = Uuid.parse("0000c0c0-0000-0000-0000-000000000000")

    private fun launch(id: String, cloudSessionId: Uuid?) = Launch(
        id = id,
        orgId = ORG,
        userId = Uuid.random(),
        dev = "ada",
        repo = null,
        agent = "claude-code",
        model = null,
        createdAt = STARTED,
        expiresAt = STARTED + 24.hours,
        cloudSessionId = cloudSessionId,
    )

    private val launches = object : LaunchesRepository {
        val stored = listOf(launch("lch_cloud", cloud), launch("lch_cli", null))

        override suspend fun create(launch: Launch, limit: LaunchLimit): Duration = error("Never written here.")

        override suspend fun find(orgId: Uuid, id: String): Launch? =
            stored.firstOrNull { it.orgId == orgId && it.id == id }
    }

    @Test
    fun aLaunchNamesTheCloudSessionThatOpenedItIfAny() = runTest {
        val read = LaunchesRunLaunches(launches)
        assertEquals(cloud, read.cloudSession(ORG, "lch_cloud"))
        assertNull(read.cloudSession(ORG, "lch_cli"), "A launch of the CLI")
        assertNull(read.cloudSession(ORG, "lch_gone"), "An unknown launch")
    }
}
