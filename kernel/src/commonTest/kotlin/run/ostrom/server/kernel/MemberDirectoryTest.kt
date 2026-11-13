// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel

import run.ostrom.server.kernel.auth.MemberDirectory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class MemberDirectoryTest {
    @Test
    fun aDirectoryThatCannotListItsMembersSaysSo() = runTest {
        val directory = object : MemberDirectory {
            override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean = true
        }
        assertNull(directory.memberIds(Uuid.random(), 10))
    }
}
