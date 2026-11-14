// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.sessions.AgentSession
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.ORG_B
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.infrastructure.RunnersCloudAgentSessions
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/** A cloud session as an agent session: what its agent's todo, waits and proposals are bound to. */
class RunnersCloudAgentSessionsTest {
    private val ada = Uuid.parse("00000000-0000-0000-0000-0000000000ad")
    private val repository = InMemoryCloudSessionsRepository()
    private val logins = object : MemberLogins {
        override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> = emptyMap()
        override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> =
            mapOf(ada to "ada").filterKeys { it in memberIds }
    }
    private val sessions = RunnersCloudAgentSessions(repository, logins)

    @Test
    fun aCloudSessionIsItsOwnersOpenWhileAnExecutionHoldsItsPodItsIdItsOneSegment() = runTest {
        val running = session(SessionState.RUNNING, ownerId = ada.toString()).also(repository::put)
        val id = running.id.toString()
        assertEquals(AgentSession("ada", open = true, listOf(id)), sessions.find(ORG_A, id))
        repository.put(running.copy(state = SessionState.PAUSED))
        assertEquals(AgentSession("ada", open = false, listOf(id)), sessions.find(ORG_A, id))
        assertNull(sessions.find(ORG_B, id), "Another organization's")
    }

    @Test
    fun anUnknownIdOrAnOwnerWhoIsNoMemberIsNoSession() = runTest {
        assertNull(sessions.find(ORG_A, "s-1"), "Not a cloud session's id")
        assertNull(sessions.find(ORG_A, Uuid.random().toString()))
        val legacy = session(SessionState.RUNNING, ownerId = "alice").also(repository::put)
        assertNull(sessions.find(ORG_A, legacy.id.toString()), "An owner id that names no member")
        val gone = session(SessionState.RUNNING, ownerId = Uuid.random().toString()).also(repository::put)
        assertNull(sessions.find(ORG_A, gone.id.toString()), "An owner who is no member any more")
        val owned = session(SessionState.RUNNING, ownerId = ada.toString()).also(repository::put)
        val unbound = RunnersCloudAgentSessions(repository, null)
        assertNull(unbound.find(ORG_A, owned.id.toString()), "Without the orgs module no owner is known")
    }
}
