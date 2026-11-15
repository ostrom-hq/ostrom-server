// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud

import kotlinx.coroutines.test.runTest
import run.ostrom.server.cloud.domain.CloudProfile
import run.ostrom.server.cloud.domain.CloudProfileDirectoryImpl
import run.ostrom.server.cloud.domain.CloudProfileSpec
import run.ostrom.server.kernel.sessions.NamedCloudProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

class CloudProfileDirectoryTest {
    @Test
    fun theDirectoryNamesTheOrganizationsProfilesAtTheirCurrentVersionOnly() = runTest {
        val org = Uuid.random()
        val profiles = MemoryProfiles()
        val id = Uuid.random()
        val at = Instant.fromEpochSeconds(1_800_000_000)
        val spec = CloudProfileSpec(clusterId = "c", namespace = "ns", image = "img")
        val first = CloudProfile(id, org, "old", 1, spec, emptySet(), at, at)
        profiles.versions += first
        profiles.versions += first.copy(name = "backend", version = 2)
        profiles.versions += first.copy(id = Uuid.random(), orgId = Uuid.random(), name = "theirs")

        assertEquals(listOf(NamedCloudProfile(id, "backend")), CloudProfileDirectoryImpl(profiles).profiles(org))
    }
}
