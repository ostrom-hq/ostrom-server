// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.AuditEntry
import dev.ostrom.server.identity.domain.AuditEvent
import dev.ostrom.server.identity.domain.AuditIntegrity
import dev.ostrom.server.identity.domain.AuditLogImpl
import dev.ostrom.server.identity.domain.AuditVerifierImpl
import dev.ostrom.server.identity.domain.ChainedAuditEvent
import dev.ostrom.server.identity.domain.InvalidAuditCursorException
import dev.ostrom.server.identity.domain.ListOrgAuditUseCaseImpl
import dev.ostrom.server.identity.domain.ORG_AUDIT_PAGE
import dev.ostrom.server.identity.domain.SecretHasher
import dev.ostrom.server.identity.domain.User
import dev.ostrom.server.identity.domain.auditCanonicalForm
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
        val verifier = AuditVerifierImpl(signer, ShortHasher)
        val list = ListOrgAuditUseCaseImpl(events, users, verifier)
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
        assertEquals(List(3) { AuditIntegrity.VERIFIED }, page.lines.map { it.integrity })
        assertNull(page.next)
    }

    /** Four lines of [orgId], sequences 1 to 4, as the log writes them. */
    private suspend fun World.fourLines() = repeat(4) {
        log.record(AuditEntry(orgId, ada.id, "budget.changed", "budget", "$it"))
    }

    private suspend fun World.integrities(action: String? = null): Map<Long, AuditIntegrity> =
        list(orgId, null, action, null).lines.associate { it.event.sequence to it.integrity }

    /** Replaces the line [sequence] of [orgId] in place, as a write in the table would. */
    private fun World.rewrite(sequence: Long, change: (AuditEvent) -> AuditEvent) {
        val index = events.events.indexOfFirst { it.entry.orgId == orgId && it.sequence == sequence }
        events.events[index] = change(events.events[index])
    }

    private suspend fun AuditEvent.rehashed(): AuditEvent =
        copy(hash = ShortHasher.hash(auditCanonicalForm(id, sequence, entry, occurredAt, previousHash)))

    @Test
    fun aLineChangedInTheTableIsAlteredAndTheNextOneStaysLinked() = runTest {
        val world = world()
        world.fourLines()

        world.rewrite(2) { it.copy(entry = it.entry.copy(action = "budget.read")) }

        val found = world.integrities()
        assertEquals(AuditIntegrity.ALTERED, found[2])
        // Line 3 was signed after the hash line 2 still carries: its link holds.
        assertEquals(listOf(AuditIntegrity.VERIFIED), listOf(1L, 3L, 4L).map { found[it] }.distinct())
    }

    @Test
    fun aLineRewrittenWithANewHashFailsItsSignatureAndUnlinksTheNext() = runTest {
        val world = world()
        world.fourLines()

        world.rewrite(2) { it.copy(entry = it.entry.copy(action = "budget.read")).rehashed() }

        val found = world.integrities()
        assertEquals(AuditIntegrity.ALTERED, found[2], "Only the instance key signs a line")
        assertEquals(AuditIntegrity.UNLINKED, found[3])
        assertEquals(AuditIntegrity.VERIFIED, found[4])
    }

    @Test
    fun anErasedLineUnlinksTheNextEvenWhenAFilterLeavesItOut() = runTest {
        val world = world()
        world.fourLines()
        world.log.record(AuditEntry(orgId, ada.id, "org.renamed", "org", "$orgId"))

        world.events.events.removeAll { it.entry.orgId == orgId && it.sequence == 4L }

        assertEquals(AuditIntegrity.UNLINKED, world.integrities()[5])
        assertEquals(mapOf(5L to AuditIntegrity.UNLINKED), world.integrities("org.renamed"))
    }

    @Test
    fun aLineSignedByAnotherKeyIsUnverifiableAndAForgedSignatureAltered() = runTest {
        val world = world()
        world.fourLines()

        world.rewrite(1) { it.copy(keyId = "retired-key") }
        world.rewrite(2) { it.copy(signature = "not base64 %") }
        world.rewrite(3) { it.copy(signature = world.events.events.first { e -> e.sequence == 4L }.signature) }

        val found = world.integrities()
        assertEquals(AuditIntegrity.UNVERIFIABLE, found[1])
        assertEquals(AuditIntegrity.ALTERED, found[2])
        assertEquals(AuditIntegrity.ALTERED, found[3], "Another line's signature")
        assertEquals(AuditIntegrity.VERIFIED, found[4])
    }

    @Test
    fun theFirstLineOfAChainFollowsNothing() = runTest {
        val world = world()
        world.fourLines()
        val first = world.events.events.first { it.entry.orgId == orgId && it.sequence == 1L }
        val second = world.events.events.first { it.entry.orgId == orgId && it.sequence == 2L }

        assertEquals(AuditIntegrity.VERIFIED, world.verifier.integrity(ChainedAuditEvent(first, null)))
        // A line found before the first one: the chain was rewritten under it.
        assertEquals(AuditIntegrity.UNLINKED, world.verifier.integrity(ChainedAuditEvent(first, "h0")))
        // A first line that claims to follow another, its hash recomputed: unlinked before its signature is read.
        val claims = first.copy(previousHash = "h0").rehashed()
        assertEquals(AuditIntegrity.UNLINKED, world.verifier.integrity(ChainedAuditEvent(claims, null)))
        // A later line with no line before it, or another one.
        assertEquals(AuditIntegrity.UNLINKED, world.verifier.integrity(ChainedAuditEvent(second, null)))
        assertEquals(AuditIntegrity.UNLINKED, world.verifier.integrity(ChainedAuditEvent(second, "h0")))
        assertEquals(AuditIntegrity.VERIFIED, world.verifier.integrity(ChainedAuditEvent(second, first.hash)))
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
        // A service key's id is a UUID, and still not a person's.
        val key = Uuid.random()
        val service = AuthenticatedPrincipal("$key", PrincipalKind.SERVICE, emptySet())
        trail.record(OrgAuditRecord(orgId, service, "budget.changed", "budget", "organization"))

        val (person, machine, odd) = log.entries
        val byKey = log.entries.last()
        val details = mapOf("a" to "1", "credential" to "browser")
        assertEquals(AuditEntry(orgId, ada.id, "budget.changed", "budget", "organization", details), person)
        assertNull(machine.actorId)
        val agentDetails = mapOf("credential" to "api_token", "actor_kind" to "agent", "actor_id" to "agent-7")
        assertEquals(agentDetails, machine.details)
        assertNull(odd.actorId)
        assertEquals("user", odd.details["actor_kind"])
        assertNull(byKey.actorId)
        assertEquals("service" to "$key", byKey.details["actor_kind"] to byKey.details["actor_id"])
    }
}
