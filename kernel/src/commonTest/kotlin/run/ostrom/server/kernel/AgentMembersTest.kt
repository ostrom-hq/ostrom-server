// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import run.ostrom.server.kernel.auth.AgentMember
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class AgentMembersTest {
    @Test
    fun anAgentMemberOfTheCliNamesNoCloudSession() {
        val member = OrgPrincipal(Uuid.random(), Uuid.random(), OrgRole.MEMBER)
        val local = AgentMember(member, "lch_1")
        assertNull(local.cloudSessionId)
        assertEquals("s-1", local.copy(cloudSessionId = "s-1").cloudSessionId)
        assertEquals(member, local.member)
        assertEquals("lch_1", local.launchId)
    }
}
