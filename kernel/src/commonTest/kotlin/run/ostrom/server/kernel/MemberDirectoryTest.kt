// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import run.ostrom.server.kernel.auth.MemberDirectory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class MemberDirectoryTest {
    @Test
    fun aDirectoryThatCannotListItsMembersSaysSo() = runTest {
        val directory = object : MemberDirectory {
            override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean = true
        }
        assertNull(directory.memberIds(Uuid.random(), 10))
        assertNull(directory.roles(Uuid.random(), 10))
        assertNull(directory.userIdOf(Uuid.random(), "m"))
        assertNull(directory.roleOf(Uuid.random(), "m"))
        assertFalse(directory.hasUser(Uuid.random(), Uuid.random()))
    }
}
