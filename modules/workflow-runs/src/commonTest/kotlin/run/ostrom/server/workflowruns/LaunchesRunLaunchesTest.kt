// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns

import run.ostrom.server.launches.domain.models.Launch
import run.ostrom.server.launches.domain.models.LaunchLimit
import run.ostrom.server.launches.domain.repositories.LaunchesRepository
import run.ostrom.server.workflowruns.fakes.ORG
import run.ostrom.server.workflowruns.fakes.STARTED
import run.ostrom.server.workflowruns.infrastructure.LaunchesRunLaunches
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

class LaunchesRunLaunchesTest {
    private val cloud = Uuid.parse("0000c0c0-0000-0000-0000-000000000000")

    private fun launch(id: String, cloudSessionId: Uuid?, repo: String? = null) = Launch(
        id = id,
        orgId = ORG,
        userId = Uuid.random(),
        dev = "ada",
        repo = repo,
        agent = "claude-code",
        model = null,
        createdAt = STARTED,
        expiresAt = STARTED + 24.hours,
        cloudSessionId = cloudSessionId,
    )

    private val launches = object : LaunchesRepository {
        val stored = listOf(launch("lch_cloud", cloud), launch("lch_cli", null, repo = "acme/api"))

        override suspend fun create(launch: Launch, limit: LaunchLimit): Duration = error("Never written here.")

        override suspend fun find(orgId: Uuid, id: String): Launch? =
            stored.firstOrNull { it.orgId == orgId && it.id == id }

        override suspend fun purge(before: Instant): Long = error("Never written here.")
    }

    @Test
    fun aLaunchNamesTheCloudSessionThatOpenedItIfAny() = runTest {
        val read = LaunchesRunLaunches(launches)
        assertEquals(cloud, read.cloudSession(ORG, "lch_cloud"))
        assertNull(read.cloudSession(ORG, "lch_cli"), "A launch of the CLI")
        assertNull(read.cloudSession(ORG, "lch_gone"), "An unknown launch")
    }

    @Test
    fun aLaunchNamesItsRepositoryIfAny() = runTest {
        val read = LaunchesRunLaunches(launches)
        assertEquals("acme/api", read.repo(ORG, "lch_cli"))
        assertNull(read.repo(ORG, "lch_cloud"), "A cloud session without a repository")
        assertNull(read.repo(ORG, "lch_gone"), "An unknown launch")
    }
}
