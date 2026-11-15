// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import kotlinx.coroutines.test.runTest
import run.ostrom.server.kernel.sessions.CloudProfileDirectory
import run.ostrom.server.kernel.sessions.NamedCloudProfile
import run.ostrom.server.kernel.sessions.has
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class CloudProfileDirectoryTest {
    private val org = Uuid.random()
    private val profile = Uuid.random()
    private val directory = CloudProfileDirectory { orgId ->
        if (orgId == org) listOf(NamedCloudProfile(profile, "default")) else emptyList()
    }

    @Test
    fun anOrganizationHasItsOwnProfile() = runTest {
        assertTrue(directory.has(org, profile))
    }

    @Test
    fun anOrganizationHasNeitherAnUnknownIdNorAnotherOrganizationsProfile() = runTest {
        assertFalse(directory.has(org, Uuid.random()))
        assertFalse(directory.has(Uuid.random(), profile))
    }
}
