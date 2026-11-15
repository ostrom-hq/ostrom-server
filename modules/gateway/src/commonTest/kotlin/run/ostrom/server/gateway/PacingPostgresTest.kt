// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway

import run.ostrom.server.gateway.domain.AdmissionEntry
import run.ostrom.server.gateway.domain.AdmissionQueueRepository
import run.ostrom.server.gateway.domain.AdmissionRequest
import run.ostrom.server.gateway.domain.AgentDirective
import run.ostrom.server.gateway.domain.ClaimExpiredException
import run.ostrom.server.gateway.domain.DeliveryStage
import run.ostrom.server.gateway.domain.DirectiveExpiredException
import run.ostrom.server.gateway.domain.DirectiveRepository
import run.ostrom.server.gateway.domain.DirectiveWrite
import run.ostrom.server.gateway.domain.Pace
import run.ostrom.server.gateway.domain.PacingAdvice
import run.ostrom.server.gateway.domain.PacingRecovery
import run.ostrom.server.gateway.domain.PacingSettings
import run.ostrom.server.gateway.domain.PacingSettingsRepository
import run.ostrom.server.gateway.domain.QueueRefusedException
import run.ostrom.server.gateway.domain.RefusalKind
import run.ostrom.server.gateway.domain.Workload
import run.ostrom.server.gateway.infrastructure.DirectiveRepositoryImpl
import run.ostrom.server.gateway.infrastructure.sessionDigest
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.sharing.SharingModule
import run.ostrom.server.testing.serverTest
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The capacity controller's storage on real PostgreSQL, as the serving role under row-level security (TeamCode's
 * `RouteDirectivePersistenceTest`, `PacingObservationPersistenceTest`): settings merged, advice by member and session
 * digest, claims and receipts, and the admission spacing in the queue's transaction.
 */
class PacingPostgresTest {
    private val modules = listOf(SecretsModule, SharingModule, GatewayModule)
    private val overrides = module { single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) } }
    private val ada = Gw.memberA.memberId

    private fun write(scope: String, interval: Duration = Duration.ZERO, expiresIn: Duration = 3.minutes): DirectiveWrite {
        val now = Clock.System.now()
        val advice = PacingAdvice(if (interval > Duration.ZERO) Pace.SLOW_DOWN else Pace.NORMAL, 2, interval, "advice")
        return DirectiveWrite(
            ada,
            scope,
            AgentDirective(Uuid.random().toString(), ada.toString(), advice, now, now + expiresIn),
            "context",
            PacingRecovery(Pace.NORMAL, now, 2.seconds),
        )
    }

    private fun call(session: String?) = AdmissionRequest(Uuid.random(), Gw.orgA, ada, "claude", Workload.INTERACTIVE, null, session = session)

    @Test
    fun settingsAreMergedAndConfinedToTheirOrg() = serverTest(modules, overrides) { server ->
        val settings = server.koin.get<PacingSettingsRepository>()
        assertEquals(PacingSettings(), settings.get(Gw.orgA))
        assertEquals(PacingSettings() to PacingSettings(observationOnly = true), settings.update(Gw.orgA, true, null))
        assertEquals(PacingSettings(true, true), settings.update(Gw.orgA, null, true).second)
        assertEquals(PacingSettings(), settings.get(Gw.orgB))
    }

    @Test
    fun adviceIsStoredByMemberAndSessionDigestAndExpires() = serverTest(modules, overrides) { server ->
        val directives = server.koin.get<DirectiveRepository>()
        val wide = write("")
        val session = write(sessionDigest("s1"))
        directives.save(Gw.orgA, listOf(wide, session, write(sessionDigest("old"), expiresIn = (-1).minutes)))
        val stored = directives.stored(Gw.orgA)
        assertEquals(setOf(ada to "", ada to sessionDigest("s1")), stored.keys, "The expired advice is purged")
        assertEquals(wide.recovery, stored.getValue(ada to "").recovery)
        assertEquals(wide.directive.advice, directives.current(Gw.orgA, ada, null)!!.advice)
        assertEquals(session.directive.id, directives.current(Gw.orgA, ada, "s1")!!.id)
        assertNull(directives.current(Gw.orgA, ada, "s2"))
        assertNull(directives.current(Gw.orgB, ada, null), "Another org reads nothing")
        assertTrue(DirectiveRepositoryImpl(server.koin.get()).stored(Gw.orgB).isEmpty())
    }

    @Test
    fun aDeliveryIsClaimedOnceAndSettledByItsOwnClaim() = serverTest(modules, overrides) { server ->
        val directives = server.koin.get<DirectiveRepository>()
        val session = write(sessionDigest("s1"))
        directives.save(Gw.orgA, listOf(session, write("")))
        val claim = assertNotNull(directives.claim(Gw.orgA, ada, "s1", DeliveryStage.EMITTED_TO_HOOK, "tok"))
        assertEquals(session.directive.id, claim.directive.id)
        assertNull(directives.claim(Gw.orgA, ada, "s1", DeliveryStage.EMITTED_TO_HOOK, "tok"), "Claimed: not twice")
        assertNotNull(directives.claim(Gw.orgA, ada, "s1", DeliveryStage.RECEIVED_BY_CLI, "tok"), "Another stage")
        assertNull(directives.claim(Gw.orgA, ada, "s2", DeliveryStage.EMITTED_TO_HOOK, "tok"), "No advice")
        val id = claim.directive.id
        assertFailsWith<ClaimExpiredException> {
            directives.acknowledge(Gw.orgA, ada, id, DeliveryStage.EMITTED_TO_HOOK, "s1", claim.claimId, "other")
        }
        directives.acknowledge(Gw.orgA, ada, id, DeliveryStage.EMITTED_TO_HOOK, "s1", claim.claimId, "tok")
        directives.acknowledge(Gw.orgA, ada, id, DeliveryStage.EMITTED_TO_HOOK, "s1", claim.claimId, "tok")
        assertNull(directives.claim(Gw.orgA, ada, "s1", DeliveryStage.EMITTED_TO_HOOK, "tok"), "Delivered: never again")
        val receipts = server.database.superuser.fetchAll("SELECT stage FROM gateway_directive_receipts").getOrThrow()
        assertEquals(1, receipts.size, "Idempotent")
        assertFailsWith<DirectiveExpiredException> {
            directives.acknowledge(Gw.orgA, ada, "gone", DeliveryStage.EMITTED_TO_HOOK, null, null, "tok")
        }
        // A new advice is delivered again.
        val next = write(sessionDigest("s1"))
        directives.save(Gw.orgA, listOf(next))
        assertEquals(next.directive.id, directives.claim(Gw.orgA, ada, "s1", DeliveryStage.EMITTED_TO_HOOK, "tok")!!.directive.id)
        // An expired claim settles nothing.
        val pending = assertNotNull(directives.claim(Gw.orgA, ada, "s1", DeliveryStage.RECEIVED_BY_CLI, "tok"))
        server.database.superuser.execute("UPDATE gateway_directive_deliveries SET claimed_until = now()").getOrThrow()
        assertFailsWith<ClaimExpiredException> {
            directives.acknowledge(Gw.orgA, ada, next.directive.id, DeliveryStage.RECEIVED_BY_CLI, "s1", pending.claimId, "tok")
        }
        // Old deliveries go with the purge, and a member holds at most the bound.
        server.database.superuser.execute(
            "UPDATE gateway_directive_deliveries SET updated_at = now() - INTERVAL '8 days'",
        ).getOrThrow()
        directives.save(Gw.orgA, emptyList())
        val left = server.database.superuser.fetchAll("SELECT stage FROM gateway_directive_deliveries").getOrThrow()
        assertEquals(0, left.size)
        server.database.superuser.execute(
            "INSERT INTO gateway_directive_deliveries SELECT org_id, member_id, md5(i::text), 'EMITTED_TO_HOOK', " +
                "'d', 'c', 't', now(), now(), now() FROM gateway_directives, generate_series(1, " +
                "${DirectiveRepository.MAX_MEMBER_DELIVERIES}) AS i WHERE scope_key = ''",
        ).getOrThrow()
        assertNull(directives.claim(Gw.orgA, ada, "s1", DeliveryStage.EMITTED_TO_HOOK, "tok"), "Past the bound")
    }

    @Test
    fun admissionsAreSpacedByTheAdviceOfTheirScope() = serverTest(modules, overrides) { server ->
        val queue = server.koin.get<AdmissionQueueRepository>()
        val directives = server.koin.get<DirectiveRepository>()
        directives.save(Gw.orgA, listOf(write("", 10.seconds), write(sessionDigest("s1"), Duration.ZERO)))
        assertEquals(AdmissionEntry.Admitted, queue.enter(call(null), first = true), "No admission yet")
        val spaced = assertFailsWith<QueueRefusedException> { queue.enter(call(null), first = true) }
        assertEquals(RefusalKind.PACING_DELAY, spaced.kind)
        assertTrue(spaced.retryIn!! in 1.seconds..10.seconds)
        assertEquals(AdmissionEntry.Admitted, queue.enter(call("s1"), first = true), "Its session's advice spaces nothing")
        assertEquals(AdmissionEntry.Admitted, queue.enter(call("s2"), first = true), "A session without advice")
        server.koin.get<PacingSettingsRepository>().update(Gw.orgA, true, null)
        assertEquals(AdmissionEntry.Admitted, queue.enter(call(null), first = true), "Observation mode spaces nothing")
        server.koin.get<PacingSettingsRepository>().update(Gw.orgA, false, null)
        server.database.superuser.execute(
            "UPDATE gateway_directives SET last_admitted_at = now() - INTERVAL '1 minute'",
        ).getOrThrow()
        assertEquals(AdmissionEntry.Admitted, queue.enter(call(null), first = true), "The interval passed")
        // A waiting call whose turn comes too soon is refused and leaves the queue.
        val waiting = call(null)
        server.database.superuser.execute(
            "INSERT INTO gateway_admissions (id, org_id, member_id, scope, workload, running, created_at, ready_at, " +
                "polled_at, expires_at) VALUES ('${waiting.id}', '${Gw.orgA}', '$ada', 'claude', 'INTERACTIVE', false, " +
                "now(), now(), now(), now() + INTERVAL '1 minute')",
        ).getOrThrow()
        assertFailsWith<QueueRefusedException> { queue.enter(waiting, first = false) }
        val left = server.database.superuser.fetchAll("SELECT id FROM gateway_admissions WHERE running = false").getOrThrow()
        assertEquals(0, left.size)
    }
}
