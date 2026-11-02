// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.AuditEntry
import dev.ostrom.server.identity.domain.AuditLogImpl
import dev.ostrom.server.identity.domain.InvalidAuditCursorException
import dev.ostrom.server.identity.domain.ListOrgAuditUseCaseImpl
import dev.ostrom.server.identity.domain.ORG_AUDIT_PAGE
import dev.ostrom.server.identity.domain.SecretHasher
import dev.ostrom.server.identity.domain.User
import dev.ostrom.server.identity.fakes.FakeHasher
import dev.ostrom.server.identity.fakes.InMemoryAuditRepository
import dev.ostrom.server.identity.fakes.InMemoryUsersRepository
import dev.ostrom.server.identity.fakes.RecordingAuditLog
import dev.ostrom.server.identity.fakes.SettableClock
import dev.ostrom.server.identity.infrastructure.IdentityOrgAuditTrail
import dev.ostrom.server.kernel.audit.OrgAuditRecord
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalChannel
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.signing.Ed25519InstanceSigner
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

class OrgAuditTest {
    private val orgId = Uuid.random()
    private val ada = User(Uuid.random(), "ada", "Ada", null, null, Instant.fromEpochSeconds(0))
    private val gone = Uuid.random()

    private class World(signer: Ed25519InstanceSigner) {
        val events = InMemoryAuditRepository()
        val users = InMemoryUsersRepository()
        val log = AuditLogImpl(events, signer, ShortHasher, SettableClock())
        val list = ListOrgAuditUseCaseImpl(events, users)
    }

    // FakeHasher wraps its input, so a chain of hashes grows with every line, escaped again each time: 50 lines run
    // out of memory. A hash of fixed length keeps a long log small.
    private object ShortHasher : SecretHasher by FakeHasher() {
        override suspend fun hash(secret: String): String = "h${secret.hashCode()}"
    }

    private suspend fun world() = World(Ed25519InstanceSigner.generate()).apply { users.users[ada.id] = ada }

    @Test
    fun anOwnerReadsTheNewestFirstWithTheActorsLogin() = runTest {
        val world = world()
        world.log.record(AuditEntry(orgId, ada.id, "org.created", "org", "$orgId"))
        world.log.record(AuditEntry(orgId, gone, "member.removed", "member", "m1"))
        world.log.record(AuditEntry(orgId, null, "org.purged", "org", "$orgId"))
        world.log.record(AuditEntry(Uuid.random(), ada.id, "org.created", "org", "other"))

        val page = world.list(orgId, null, null, null)

        assertEquals(listOf("org.purged", "member.removed", "org.created"), page.lines.map { it.event.entry.action })
        assertEquals(listOf(null, null, "ada"), page.lines.map { it.actorLogin })
        assertNull(page.next)
    }

    @Test
    fun theFiltersKeepOnePersonOrOneEvent() = runTest {
        val world = world()
        world.log.record(AuditEntry(orgId, ada.id, "org.created", "org", "$orgId"))
        world.log.record(AuditEntry(orgId, gone, "org.renamed", "org", "$orgId"))

        assertEquals(listOf("org.created"), world.list(orgId, "ada", null, null).lines.map { it.event.entry.action })
        assertEquals(listOf(gone), world.list(orgId, null, "org.renamed", null).lines.map { it.event.entry.actorId })
        // Nobody by that login: no event, without looking at the log.
        assertEquals(emptyList(), world.list(orgId, "nobody", null, null).lines)
    }

    @Test
    fun aFullPageGivesTheCursorOfTheNext() = runTest {
        val world = world()
        repeat(ORG_AUDIT_PAGE + 2) { world.log.record(AuditEntry(orgId, ada.id, "budget.changed", "budget", "$it")) }

        val first = world.list(orgId, null, null, null)
        assertEquals(ORG_AUDIT_PAGE, first.lines.size)
        assertEquals("3", first.next)
        val last = world.list(orgId, null, null, first.next)
        assertEquals(listOf(2L, 1L), last.lines.map { it.event.sequence })
        assertNull(last.next)
    }

    @Test
    fun aLastPageThatIsExactlyFullGivesNoCursor() = runTest {
        val world = world()
        repeat(ORG_AUDIT_PAGE) { world.log.record(AuditEntry(orgId, ada.id, "budget.changed", "budget", "$it")) }

        val only = world.list(orgId, null, null, null)

        assertEquals(ORG_AUDIT_PAGE, only.lines.size)
        assertNull(only.next, "No cursor to an empty page")
    }

    @Test
    fun aCursorThatIsNotOneIsRefused() = runTest {
        val world = world()
        for (before in listOf("abc", "0", "-4", "")) {
            assertFailsWith<InvalidAuditCursorException>(before) { world.list(orgId, null, null, before) }
        }
    }

    @Test
    fun theTrailNamesThePersonAndTheCredentialTheyUsed() = runTest {
        val log = RecordingAuditLog()
        val trail = IdentityOrgAuditTrail(log)
        val session =
            AuthenticatedPrincipal("${ada.id}", PrincipalKind.USER, setOf("user:*"), channel = PrincipalChannel.BROWSER)
        trail.record(OrgAuditRecord(orgId, session, "budget.changed", "budget", "organization", mapOf("a" to "1")))
        val agent = AuthenticatedPrincipal("agent-7", PrincipalKind.AGENT, emptySet())
        trail.record(OrgAuditRecord(orgId, agent, "mcp.settings.changed", "settings", "mcp"))
        val oddPerson = AuthenticatedPrincipal("not-an-id", PrincipalKind.USER, emptySet())
        trail.record(OrgAuditRecord(orgId, oddPerson, "inference.policy.changed", "policy", "models"))

        val (person, machine, odd) = log.entries
        val details = mapOf("a" to "1", "credential" to "browser")
        assertEquals(AuditEntry(orgId, ada.id, "budget.changed", "budget", "organization", details), person)
        assertNull(machine.actorId)
        val agentDetails = mapOf("credential" to "api_token", "actor_kind" to "agent", "actor_id" to "agent-7")
        assertEquals(agentDetails, machine.details)
        assertNull(odd.actorId)
        assertEquals("user", odd.details["actor_kind"])
    }
}
