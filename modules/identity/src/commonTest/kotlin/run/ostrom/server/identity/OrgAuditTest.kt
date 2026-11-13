// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity

import run.ostrom.server.identity.domain.AuditEntry
import run.ostrom.server.identity.domain.AuditEvent
import run.ostrom.server.identity.domain.AuditIntegrity
import run.ostrom.server.identity.domain.AuditLogImpl
import run.ostrom.server.identity.domain.AuditVerifierImpl
import run.ostrom.server.identity.domain.ChainedAuditEvent
import run.ostrom.server.identity.domain.InvalidAuditCursorException
import run.ostrom.server.identity.domain.ListOrgAuditUseCaseImpl
import run.ostrom.server.identity.domain.ORG_AUDIT_PAGE
import run.ostrom.server.identity.domain.SecretHasher
import run.ostrom.server.identity.domain.User
import run.ostrom.server.identity.domain.auditCanonicalForm
import run.ostrom.server.identity.fakes.FakeHasher
import run.ostrom.server.identity.fakes.InMemoryAuditRepository
import run.ostrom.server.identity.fakes.InMemoryUsersRepository
import run.ostrom.server.identity.fakes.RecordingAuditLog
import run.ostrom.server.identity.fakes.SettableClock
import run.ostrom.server.identity.infrastructure.IdentityOrgAuditTrail
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.signing.CurrentKeyring
import run.ostrom.server.kernel.signing.Ed25519InstanceSigner
import run.ostrom.server.kernel.signing.InstanceKeyring
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

    private class World(signer: Ed25519InstanceSigner, keys: InstanceKeyring = CurrentKeyring(signer)) {
        val events = InMemoryAuditRepository()
        val users = InMemoryUsersRepository()
        val log = AuditLogImpl(events, signer, ShortHasher, SettableClock())
        val verifier = AuditVerifierImpl(keys, ShortHasher)
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
    private suspend fun World.rewrite(sequence: Long, change: suspend (AuditEvent) -> AuditEvent) {
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
    fun aKeyTheInstanceNeverHadOrAForgedSignatureIsAltered() = runTest {
        val world = world()
        world.fourLines()

        world.rewrite(1) { it.copy(keyId = "0000000000000000") }
        world.rewrite(2) { it.copy(signature = "not base64 %") }
        world.rewrite(3) { it.copy(signature = world.events.events.first { e -> e.sequence == 4L }.signature) }

        val found = world.integrities()
        assertEquals(AuditIntegrity.ALTERED, found[1], "Not a key of the instance")
        assertEquals(AuditIntegrity.ALTERED, found[2])
        assertEquals(AuditIntegrity.ALTERED, found[3], "Another line's signature")
        assertEquals(AuditIntegrity.VERIFIED, found[4])
    }

    @Test
    fun theNewestLineRewrittenUnderAnotherKeyIdIsAlteredNotExcused() = runTest {
        val world = world()
        world.fourLines()

        // The hash recomputed (no key needed) and a key id the server does not hold: the signature still decides.
        world.rewrite(4) { it.copy(entry = it.entry.copy(action = "budget.read"), keyId = "x").rehashed() }

        assertEquals(AuditIntegrity.ALTERED, world.integrities()[4])
    }

    @Test
    fun aLineSignedByAPastKeyOfTheInstanceIsVerified() = runTest {
        val past = Ed25519InstanceSigner.generate()
        val current = Ed25519InstanceSigner.generate()
        val ring = object : InstanceKeyring {
            override suspend fun verify(keyId: String, data: ByteArray, signature: ByteArray): Boolean? =
                listOf(past, current).firstOrNull { it.keyId == keyId }?.verify(data, signature)
        }
        val world = World(past, ring).apply { users.users[ada.id] = ada }
        world.fourLines()

        assertEquals(List(4) { AuditIntegrity.VERIFIED }, world.integrities().values.toList())
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
        assertEquals(emptyList(), world.list(orgId, "nobody", null, null).lines)
    }

    @Test
    fun anUnknownLoginReadsTheLogLikeAKnownOne() = runTest {
        // AUDIT-SECU-LECTURES B1: the same reads for a login of the instance and one that does not exist, so the time
        // of the answer tells them apart no more than its body.
        val world = world()
        world.log.record(AuditEntry(orgId, ada.id, "org.created", "org", "$orgId"))
        val elsewhere = User(Uuid.random(), "grace", "Grace", null, null, Instant.fromEpochSeconds(0))
        world.users.users[elsewhere.id] = elsewhere

        val unknown = world.list(orgId, "nobody", null, null)
        val other = world.list(orgId, "grace", null, null)

        assertEquals(emptyList(), unknown.lines)
        assertEquals(emptyList(), other.lines)
        assertEquals(listOf(Uuid.NIL, elsewhere.id), world.events.orgReads.map { it.actorId })
        assertEquals(1, world.events.orgReads.map { it.limit }.toSet().size, "the same query")
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
