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
import run.ostrom.server.gateway.infrastructure.GatewayAdmissions
import run.ostrom.server.gateway.infrastructure.GatewayDirectiveDeliveries
import run.ostrom.server.gateway.infrastructure.GatewayDirectiveReceipts
import run.ostrom.server.gateway.infrastructure.sessionDigest
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.sharing.SharingModule
import run.ostrom.server.testing.serverTest
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
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
import kotlin.time.Instant
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

    private fun write(
        scope: String,
        interval: Duration = Duration.ZERO,
        expiresIn: Duration = 3.minutes,
    ): DirectiveWrite {
        // PostgreSQL keeps microseconds: the instants written are read back equal.
        val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
        val advice = PacingAdvice(if (interval > Duration.ZERO) Pace.SLOW_DOWN else Pace.NORMAL, 2, interval, "advice")
        return DirectiveWrite(
            ada,
            scope,
            AgentDirective(Uuid.random().toString(), ada.toString(), advice, now, now + expiresIn),
            "context",
            PacingRecovery(Pace.NORMAL, now, 2.seconds),
        )
    }

    private fun call(session: String?) =
        AdmissionRequest(Uuid.random(), Gw.orgA, ada, "claude", Workload.INTERACTIVE, null, session = session)

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
        // Without a recovery under way, none is read back.
        directives.save(Gw.orgA, listOf(DirectiveWrite(ada, "", wide.directive, "context", null)))
        assertNull(directives.stored(Gw.orgA).getValue(ada to "").recovery)
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
        assertFailsWith<ClaimExpiredException>("A session's receipt without its claim settles nothing") {
            directives.acknowledge(Gw.orgA, ada, id, DeliveryStage.EMITTED_TO_HOOK, "s1", null, "tok")
        }
        directives.acknowledge(Gw.orgA, ada, id, DeliveryStage.EMITTED_TO_HOOK, "s1", claim.claimId, "tok")
        directives.acknowledge(Gw.orgA, ada, id, DeliveryStage.EMITTED_TO_HOOK, "s1", claim.claimId, "tok")
        assertNull(directives.claim(Gw.orgA, ada, "s1", DeliveryStage.EMITTED_TO_HOOK, "tok"), "Delivered: never again")
        val receipts = server.asSuperuser { GatewayDirectiveReceipts.selectAll().count() }
        assertEquals(1L, receipts, "Idempotent")
        assertFailsWith<DirectiveExpiredException> {
            directives.acknowledge(Gw.orgA, ada, "gone", DeliveryStage.EMITTED_TO_HOOK, null, null, "tok")
        }
        // The member-wide advice is acknowledged without a claim.
        val wideId = directives.stored(Gw.orgA).getValue(ada to "").directive.id
        directives.acknowledge(Gw.orgA, ada, wideId, DeliveryStage.RECEIVED_BY_CLI, null, null, "tok")
        // A new advice is delivered again.
        val next = write(sessionDigest("s1"))
        directives.save(Gw.orgA, listOf(next))
        val again = assertNotNull(directives.claim(Gw.orgA, ada, "s1", DeliveryStage.EMITTED_TO_HOOK, "tok"))
        assertEquals(next.directive.id, again.directive.id)
        // An expired claim settles nothing.
        val pending = assertNotNull(directives.claim(Gw.orgA, ada, "s1", DeliveryStage.RECEIVED_BY_CLI, "tok"))
        server.superuserSql("UPDATE gateway_directive_deliveries SET claimed_until = now()")
        assertFailsWith<ClaimExpiredException> {
            val stage = DeliveryStage.RECEIVED_BY_CLI
            directives.acknowledge(Gw.orgA, ada, next.directive.id, stage, "s1", pending.claimId, "tok")
        }
        // Old deliveries go with the purge, and a member holds at most the bound.
        server.superuserSql("UPDATE gateway_directive_deliveries SET updated_at = now() - INTERVAL '8 days'")
        directives.save(Gw.orgA, emptyList())
        val left = server.asSuperuser { GatewayDirectiveDeliveries.selectAll().count() }
        assertEquals(0L, left)
        server.superuserSql(
            "INSERT INTO gateway_directive_deliveries SELECT org_id, member_id, md5(i::text), 'EMITTED_TO_HOOK', " +
                "'d', 'c', 't', now(), now(), now() FROM gateway_directives, generate_series(1, ?) AS i " +
                "WHERE scope_key = ''",
            listOf(IntegerColumnType() to DirectiveRepository.MAX_MEMBER_DELIVERIES),
        )
        assertNull(directives.claim(Gw.orgA, ada, "s1", DeliveryStage.EMITTED_TO_HOOK, "tok"), "Past the bound")
        // The org's bound holds across its members.
        server.asSuperuser { GatewayDirectiveDeliveries.deleteAll() }
        server.superuserSql(
            "INSERT INTO gateway_directive_deliveries SELECT org_id, gen_random_uuid(), md5(i::text), " +
                "'EMITTED_TO_HOOK', 'd', 'c', 't', now(), now(), now() FROM gateway_directives, " +
                "generate_series(1, ?) AS i WHERE scope_key = ''",
            listOf(IntegerColumnType() to DirectiveRepository.MAX_DELIVERIES),
        )
        assertNull(directives.claim(Gw.orgA, ada, "s1", DeliveryStage.EMITTED_TO_HOOK, "tok"), "Past the org's bound")
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
        val unspaced = queue.enter(call("s1"), first = true)
        assertEquals(AdmissionEntry.Admitted, unspaced, "Its session's advice spaces nothing")
        val again = queue.enter(call("s1"), first = true)
        assertEquals(AdmissionEntry.Admitted, again, "Nor once a call of the session was admitted")
        val unadvised = assertFailsWith<QueueRefusedException>("A session without advice: its member's") {
            queue.enter(call("s2"), first = true)
        }
        assertEquals(RefusalKind.PACING_DELAY, unadvised.kind)
        server.koin.get<PacingSettingsRepository>().update(Gw.orgA, true, null)
        assertEquals(AdmissionEntry.Admitted, queue.enter(call(null), first = true), "Observation mode spaces nothing")
        server.koin.get<PacingSettingsRepository>().update(Gw.orgA, false, null)
        server.superuserSql("UPDATE gateway_directives SET last_admitted_at = now() - INTERVAL '1 minute'")
        val admitted = call(null)
        assertEquals(AdmissionEntry.Admitted, queue.enter(admitted, first = true), "The interval passed")
        // The call admitted last goes back to wait (the accounts were busy): its own admission never spaces it.
        queue.requeue(Gw.orgA, admitted.id)
        assertEquals(AdmissionEntry.Admitted, queue.enter(admitted, first = false), "Its own admission")
        // A waiting call whose turn comes too soon is refused; its decision rolls back, AwaitAdmission releases it.
        val waiting = call(null)
        server.asSuperuser {
            GatewayAdmissions.insert {
                it[id] = waiting.id
                it[orgId] = Gw.orgA
                it[memberId] = ada
                it[scope] = "claude"
                it[workload] = "INTERACTIVE"
                it[running] = false
                it[createdAt] = Clock.System.now()
                it[readyAt] = Clock.System.now()
                it[polledAt] = Clock.System.now()
                it[expiresAt] = Clock.System.now() + 1.minutes
            }
        }
        assertFailsWith<QueueRefusedException> { queue.enter(waiting, first = false) }
        val waitingLeft = server.asSuperuser {
            GatewayAdmissions.selectAll()
                .where { (GatewayAdmissions.running eq false) and (GatewayAdmissions.id eq waiting.id) }.count()
        }
        assertEquals(1L, waitingLeft, "Still waiting until released")
    }
}
