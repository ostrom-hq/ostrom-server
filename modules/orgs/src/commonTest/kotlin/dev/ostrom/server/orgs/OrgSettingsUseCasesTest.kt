// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs

import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.kernel.module.OrgDeletionListener
import dev.ostrom.server.orgs.domain.CreateOrgUseCaseImpl
import dev.ostrom.server.orgs.domain.DELETION_GRACE
import dev.ostrom.server.orgs.domain.DeleteOrgUseCaseImpl
import dev.ostrom.server.orgs.domain.DeletionPass
import dev.ostrom.server.orgs.domain.InvalidOrgRequestException
import dev.ostrom.server.orgs.domain.Member
import dev.ostrom.server.orgs.domain.Membership
import dev.ostrom.server.orgs.domain.NotAllowedException
import dev.ostrom.server.orgs.domain.OrgChange
import dev.ostrom.server.orgs.domain.OrgStepUpRequiredException
import dev.ostrom.server.orgs.domain.SettleOrgDeletionsUseCase
import dev.ostrom.server.orgs.domain.SettleOrgDeletionsUseCaseImpl
import dev.ostrom.server.orgs.domain.UpdateOrgUseCaseImpl
import dev.ostrom.server.orgs.domain.validTimeZone
import dev.ostrom.server.orgs.fakes.OrgsWorld
import dev.ostrom.server.orgs.infrastructure.OrgDeletionWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Records what a module was told, and fails when told to. */
private class RecordingListener : OrgDeletionListener {
    val calls = mutableListOf<String>()
    var failOn: Uuid? = null
    var failure: Exception = IllegalStateException("module down")

    override suspend fun deleted(orgId: Uuid) {
        if (orgId == failOn) throw failure
        calls += "deleted $orgId"
    }

    override suspend fun purge(orgId: Uuid) {
        if (orgId == failOn) throw failure
        calls += "purge $orgId"
    }
}

/** Records log lines. */
private class RecordingLogger(val lines: MutableList<String> = mutableListOf()) : Logger {
    override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
        lines += "$level $message $fields"
    }

    override fun with(fields: Map<String, Any?>): Logger = this
}

class OrgSettingsUseCasesTest {
    private val world = OrgsWorld()
    private val create =
        CreateOrgUseCaseImpl(world.orgs, world.people, world.seats, world.limiter, world.audit, world.clock)
    private val update = UpdateOrgUseCaseImpl(world.orgs, world.members, world.audit)
    private val delete = DeleteOrgUseCaseImpl(world.orgs, world.members, world.audit, world.clock)
    private val listener = RecordingListener()
    private val settle = SettleOrgDeletionsUseCaseImpl(world.deletions, listOf(listener), world.clock)

    private fun join(membership: Membership, personId: Uuid, role: OrgRole) {
        val member = Member(Uuid.random(), membership.org.id, personId, role, world.clock.now)
        world.store.members[member.id] = member
    }

    @Test
    fun onlyKnownZonesAreAccepted() {
        assertEquals("UTC", validTimeZone("UTC"))
        assertEquals("Europe/Paris", validTimeZone("Europe/Paris"))
        assertEquals("America/Argentina/Buenos_Aires", validTimeZone("America/Argentina/Buenos_Aires"))
        for (zone in listOf("", "Mars/Olympus", "+02:00", "europe/paris", "Europe/Paris ")) {
            assertFailsWith<InvalidOrgRequestException>(zone) { validTimeZone(zone) }
        }
    }

    @Test
    fun ownersAndAdminsChangeTheNameAndTheTimeZoneEachAudited() = runTest {
        val ada = world.people.add("ada", null)
        val acme = create(ada.id, "Acme", null)
        val bob = world.people.add("bob", null)
        join(acme, bob.id, OrgRole.ADMIN)
        assertEquals("UTC", acme.org.timeZone, "UTC until set")

        val renamed = update(acme.org.id, ada.id, OrgChange(name = " Acme Labs "))
        assertEquals("Acme Labs", renamed.org.name)
        assertEquals(OrgRole.OWNER, renamed.member.role)
        assertEquals("org.renamed" to mapOf("from" to "Acme", "to" to "Acme Labs"), world.audit.events.last())

        val zoned = update(acme.org.id, bob.id, OrgChange(timeZone = "Europe/Paris", slug = "acme"))
        assertEquals("Europe/Paris", zoned.org.timeZone)
        assertEquals("Acme Labs", zoned.org.name)
        assertEquals(zoned.org, world.orgs.get(acme.org.id))
        assertEquals(
            "org.time_zone.changed" to mapOf("from" to "UTC", "to" to "Europe/Paris"),
            world.audit.events.last(),
        )

        val both = update(acme.org.id, ada.id, OrgChange(name = "Acme", timeZone = "Asia/Tokyo"))
        assertEquals("Acme" to "Asia/Tokyo", both.org.name to both.org.timeZone)
        assertEquals(
            listOf("org.renamed", "org.time_zone.changed"),
            world.audit.events.takeLast(2).map { it.first },
        )
    }

    @Test
    fun aChangeThatChangesNothingWritesNothing() = runTest {
        val ada = world.people.add("ada", null)
        val acme = create(ada.id, "Acme", null)
        val events = world.audit.events.size

        assertEquals(acme.org, update(acme.org.id, ada.id, OrgChange()).org)
        assertEquals(acme.org, update(acme.org.id, ada.id, OrgChange("Acme", "UTC", "acme")).org)

        assertEquals(0, world.orgs.updates)
        assertEquals(events, world.audit.events.size)
    }

    @Test
    fun theShortNameNeverChangesAndBadValuesAreRefused() = runTest {
        val ada = world.people.add("ada", null)
        val acme = create(ada.id, "Acme", null)
        val bob = world.people.add("bob", null)
        join(acme, bob.id, OrgRole.MEMBER)

        assertFailsWith<InvalidOrgRequestException> { update(acme.org.id, ada.id, OrgChange(slug = "acme-labs")) }
        assertFailsWith<InvalidOrgRequestException> { update(acme.org.id, ada.id, OrgChange(name = " ")) }
        assertFailsWith<InvalidOrgRequestException> { update(acme.org.id, ada.id, OrgChange(timeZone = "Mars/Base")) }
        assertFailsWith<NotAllowedException> { update(acme.org.id, bob.id, OrgChange(timeZone = "Europe/Paris")) }
        assertEquals(acme.org, world.orgs.get(acme.org.id))
        assertEquals(0, world.orgs.updates)
    }

    @Test
    fun onlyTheOwnerSignedInLessThanFiveMinutesAgoDeletesAfterTypingTheShortName() = runTest {
        val ada = world.people.add("ada", null)
        val acme = create(ada.id, "Acme", null)
        val bob = world.people.add("bob", null)
        join(acme, bob.id, OrgRole.ADMIN)
        val fresh = world.clock.now - 1.minutes

        assertFailsWith<NotAllowedException> { delete(acme.org.id, bob.id, "acme", fresh) }
        assertFailsWith<InvalidOrgRequestException> { delete(acme.org.id, ada.id, "Acme", fresh) }
        assertFailsWith<OrgStepUpRequiredException> { delete(acme.org.id, ada.id, "acme", null) }
        val stale = world.clock.now - 5.minutes - 1.seconds
        val error = assertFailsWith<OrgStepUpRequiredException> { delete(acme.org.id, ada.id, "acme", stale) }
        assertEquals("step_up_required", error.type)
        assertEquals(acme.org, world.orgs.get(acme.org.id), "Nothing deleted")

        delete(acme.org.id, ada.id, "acme", world.clock.now - 5.minutes)

        assertNull(world.orgs.get(acme.org.id))
        assertEquals(emptyList(), world.members.list(acme.org.id))
        assertEquals(world.clock.now + 30.days, world.store.purgeAfter[acme.org.id])
        assertEquals(30.days, DELETION_GRACE)
        assertEquals(
            "org.deleted" to mapOf("slug" to "acme", "purge_after" to "2026-11-29T10:00:00Z"),
            world.audit.events.last(),
        )
        // Its free claim is released: the same person can start a new free team, never under the same short name.
        assertEquals("acme-again", create(ada.id, "Acme again", null).org.slug)
    }

    @Test
    fun aDeletionReachesEveryModuleOnceThenTheOrganizationIsErasedAfterThirtyDays() = runTest {
        val ada = world.people.add("ada", null)
        val acme = create(ada.id, "Acme", null)
        delete(acme.org.id, ada.id, "acme", world.clock.now)
        val id = acme.org.id

        assertEquals(DeletionPass(settled = 1, purged = 0, failed = 0), settle())
        assertEquals(DeletionPass(0, 0, 0), settle(), "Told once")
        world.clock.now += 30.days - 1.seconds
        assertEquals(DeletionPass(0, 0, 0), settle(), "Kept for 30 days")
        world.clock.now += 1.seconds
        assertEquals(DeletionPass(settled = 0, purged = 1, failed = 0), settle())
        assertEquals(DeletionPass(0, 0, 0), settle(), "Erased once")

        assertEquals(listOf("deleted $id", "purge $id"), listener.calls)
        val tombstone = world.store.orgs.getValue(id)
        assertEquals(Triple("", "acme", "UTC"), Triple(tombstone.name, tombstone.slug, tombstone.timeZone))
        assertEquals(true, world.orgs.slugTaken("acme"), "A link to it never leads to someone else")
    }

    @Test
    fun aModuleThatFailsOnOneOrganizationHoldsOnlyThatOneUntilTheNextPass() = runTest {
        val ada = world.people.add("ada", null)
        val acme = create(ada.id, "Acme", null)
        val bob = world.people.add("bob", null)
        val globex = create(bob.id, "Globex", "globex")
        delete(acme.org.id, ada.id, "acme", world.clock.now)
        delete(globex.org.id, bob.id, "globex", world.clock.now)
        listener.failOn = acme.org.id

        assertEquals(DeletionPass(settled = 1, purged = 0, failed = 1), settle())
        world.clock.now += 31.days
        assertEquals(DeletionPass(settled = 0, purged = 1, failed = 1), settle(), "Not erased before it is settled")
        listener.failOn = null
        assertEquals(DeletionPass(settled = 1, purged = 1, failed = 0), settle())
        listener.failOn = globex.org.id
        assertEquals(DeletionPass(0, 0, 0), settle())
        assertEquals(
            listOf(
                "deleted ${globex.org.id}",
                "purge ${globex.org.id}",
                "deleted ${acme.org.id}",
                "purge ${acme.org.id}",
            ),
            listener.calls,
        )
    }

    @Test
    fun aCancellationStopsThePass() = runTest {
        val ada = world.people.add("ada", null)
        val acme = create(ada.id, "Acme", null)
        delete(acme.org.id, ada.id, "acme", world.clock.now)
        listener.failOn = acme.org.id
        listener.failure = CancellationException("stopped")

        assertFailsWith<CancellationException> { settle() }
        world.clock.now += 31.days
        world.store.settled += acme.org.id
        assertFailsWith<CancellationException> { settle() }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class) // The test scheduler's clock proves the pauses.
    fun theWorkerLogsOnlyWhatIsLeftAndGoesOnAfterAFailedPass() = runTest {
        val logger = RecordingLogger()
        val results =
            ArrayDeque<() -> DeletionPass>(
                listOf(
                    { DeletionPass(2, 1, 0) },
                    { DeletionPass(0, 0, 3) },
                    { throw IllegalStateException("secret detail") },
                    { DeletionPass(0, 0, 0) },
                )
            )
        val passes = object : SettleOrgDeletionsUseCase {
            override suspend fun invoke(): DeletionPass = results.removeFirst()()
        }

        OrgDeletionWorker(passes, logger, interval = 1.minutes, passes = 4).run()

        assertEquals(0, results.size)
        assertEquals(
            listOf(
                "WARN organization deletions left for the next pass {settled=0, purged=0, failed=3}",
                "ERROR organization deletion pass failed {error=IllegalStateException}",
            ),
            logger.lines,
        )
        assertEquals(3.minutes.inWholeMilliseconds, testScheduler.currentTime, "A pause between passes, none after")
    }

    @Test
    fun theWorkerStopsWhenCancelled() = runTest {
        val passes = object : SettleOrgDeletionsUseCase {
            override suspend fun invoke(): DeletionPass = throw CancellationException("stopped")
        }
        assertFailsWith<CancellationException> { OrgDeletionWorker(passes, RecordingLogger()).run() }
    }
}
