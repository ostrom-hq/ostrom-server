// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches

import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.services.GateNodeResolver
import dev.ostrom.server.launches.domain.models.HooksCheck
import dev.ostrom.server.launches.fakes.FixedClock
import dev.ostrom.server.launches.fakes.ORG
import dev.ostrom.server.launches.fakes.OTHER_ORG
import dev.ostrom.server.launches.infrastructure.OrgsHooksCheckRules
import dev.ostrom.server.orgs.domain.Member
import dev.ostrom.server.orgs.domain.Membership
import dev.ostrom.server.orgs.domain.Org
import dev.ostrom.server.orgs.domain.OrgsRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The gates' enforcement is read once a minute per organization and repository, never on each launch. */
class OrgsHooksCheckRulesTest {
    private val clock = FixedClock()

    /** Only `get` is read by the rules. */
    private class Orgs(val deadlines: Map<Uuid, Int>) : OrgsRepository {
        override suspend fun get(id: Uuid): Org? = deadlines[id]?.let {
            Org(id, "o", "O", null, null, Instant.parse("2026-11-03T10:00:00Z"), hooksDeadlineSeconds = it)
        }

        override suspend fun create(org: Org, owner: Member, isPaying: suspend (Uuid) -> Boolean): Org? = TODO()
        override suspend fun findBySlug(slug: String): Org? = TODO()
        override suspend fun slugTaken(slug: String): Boolean = TODO()
        override suspend fun update(id: Uuid, name: String?, timeZone: String?, hooksDeadlineSeconds: Int?) = TODO()
        override suspend fun delete(id: Uuid, at: Instant, purgeAfter: Instant) = TODO()
        override suspend fun listOfUser(userId: Uuid): List<Membership> = TODO()
    }

    private class CountingGates(var enforced: Boolean) : GateNodeResolver {
        val asked = mutableListOf<Pair<Uuid, String?>>()

        override suspend fun resolve(orgId: Uuid, repo: String?, actionClass: String): GateNode? = null

        override suspend fun enforcesAny(orgId: Uuid, repo: String?): Boolean {
            asked += orgId to repo
            return enforced
        }
    }

    @Test
    fun theEnforcementIsReadOncePerMinuteForAnOrganizationAndRepository() = runTest {
        val gates = CountingGates(true)
        val rules = OrgsHooksCheckRules(Orgs(mapOf(ORG to 30, OTHER_ORG to 60)), gates, clock)

        assertEquals(HooksCheck(30, true), rules.check(ORG, "acme/api"))
        gates.enforced = false
        assertEquals(HooksCheck(30, true), rules.check(ORG, "acme/api"), "Read a second ago: kept")
        assertEquals(1, gates.asked.size)
        assertEquals(HooksCheck(30, false), rules.check(ORG, null), "Another repository")
        assertEquals(HooksCheck(60, false), rules.check(OTHER_ORG, "acme/api"), "Another organization")
        assertEquals(3, gates.asked.size)

        clock.now += 59.seconds
        rules.check(ORG, "acme/api")
        assertEquals(3, gates.asked.size, "Still inside the minute")
        clock.now += 2.seconds
        assertEquals(HooksCheck(30, false), rules.check(ORG, "acme/api"), "Read again after a minute")
        assertEquals(4, gates.asked.size)
    }

    @Test
    fun anUnknownOrganizationGetsTheDefaultWaitAndTheCacheNeverGrowsPastItsBound() = runTest {
        val gates = CountingGates(false)
        val rules = OrgsHooksCheckRules(Orgs(emptyMap()), gates, clock, maxEntries = 2)

        assertEquals(HooksCheck(15, false), rules.check(ORG, "a/a"))
        rules.check(ORG, "b/b")
        rules.check(ORG, "c/c") // Over the bound: the cache starts again.
        rules.check(ORG, "c/c")
        assertEquals(3, gates.asked.size, "c/c was kept after the restart")
        rules.check(ORG, "a/a")
        assertEquals(4, gates.asked.size, "a/a was dropped by it")
    }
}
