// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel

import run.ostrom.server.kernel.sessions.AgentSession
import run.ostrom.server.kernel.sessions.AgentSessions
import run.ostrom.server.kernel.sessions.SessionEndListener
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class AgentSessionsTest {
    @Test
    fun anAgentSessionIsFoundByAnyOfItsSegmentsAndItsEndIsTold() = runTest {
        val session = AgentSession(owner = "ada", open = false, segments = listOf("s-1", "s-1~r2"))
        val sessions = object : AgentSessions {
            override suspend fun find(orgId: Uuid, sessionId: String) = session.takeIf { sessionId in it.segments }
        }
        val org = Uuid.random()
        assertEquals(session, sessions.find(org, "s-1~r2"))
        assertNull(sessions.find(org, "s-2"))
        val ended = mutableListOf<List<String>>()
        val listener = SessionEndListener { _, segments -> ended += segments }
        listener.ended(org, session.segments)
        assertEquals(listOf(listOf("s-1", "s-1~r2")), ended)
        assertEquals(AgentSession("ada", false, listOf("s-1", "s-1~r2")), session.copy())
    }
}
