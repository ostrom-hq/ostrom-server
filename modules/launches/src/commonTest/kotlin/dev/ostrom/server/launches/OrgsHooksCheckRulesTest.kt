// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches

import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.services.GateNodeResolver
import dev.ostrom.server.launches.domain.models.HooksCheck
import dev.ostrom.server.launches.fakes.ORG
import dev.ostrom.server.launches.fakes.OTHER_ORG
import dev.ostrom.server.launches.infrastructure.OrgsHooksCheckRules
import dev.ostrom.server.orgs.domain.Member
import dev.ostrom.server.orgs.domain.Membership
import dev.ostrom.server.orgs.domain.Org
import dev.ostrom.server.orgs.domain.OrgEvent
import dev.ostrom.server.orgs.domain.OrgsRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The gates' enforcement is read once per pinned package of an organization and repository, never on each launch. */
class OrgsHooksCheckRulesTest {
    /** Only `get` is read by the rules. */
    private class Orgs(val deadlines: Map<Uuid, Int>) : OrgsRepository {
        override suspend fun get(id: Uuid): Org? = deadlines[id]?.let {
            Org(id, "o", "O", null, null, Instant.parse("2026-11-03T10:00:00Z"), hooksDeadlineSeconds = it)
        }

        override suspend fun create(org: Org, owner: Member, isPaying: suspend (Uuid) -> Boolean): Org? = TODO()
        override suspend fun findBySlug(slug: String): Org? = TODO()
        override suspend fun slugTaken(slug: String): Boolean = TODO()
        override suspend fun update(id: Uuid, name: String?, timeZone: String?, hooksDeadlineSeconds: Int?) = TODO()
        override suspend fun delete(id: Uuid, at: Instant, event: OrgEvent, purgeAfter: Instant): Boolean = TODO()
        override suspend fun listOfUser(userId: Uuid): List<Membership> = TODO()
    }

    private class CountingGates(var enforced: Boolean, var stamp: String?) : GateNodeResolver {
        val asked = mutableListOf<Pair<Uuid, String?>>()

        override suspend fun resolve(orgId: Uuid, repo: String?, actionClass: String): GateNode? = null

        override suspend fun policyStamp(orgId: Uuid, repo: String?): String? = stamp

        override suspend fun enforcesAny(orgId: Uuid, repo: String?): Boolean {
            asked += orgId to repo
            return enforced
        }
    }

    @Test
    fun theEnforcementIsReadOncePerPinnedPackageNeverOnATimer() = runTest {
        val gates = CountingGates(true, "sha-1")
        val rules = OrgsHooksCheckRules(Orgs(mapOf(ORG to 30, OTHER_ORG to 60)), gates)

        assertEquals(HooksCheck(30, true), rules.check(ORG, "acme/api"))
        gates.enforced = false
        assertEquals(HooksCheck(30, true), rules.check(ORG, "acme/api"), "Same package: kept, however late")
        assertEquals(1, gates.asked.size)
        assertEquals(HooksCheck(30, false), rules.check(ORG, null), "Another repository")
        assertEquals(HooksCheck(60, false), rules.check(OTHER_ORG, "acme/api"), "Another organization")
        assertEquals(3, gates.asked.size)

        gates.stamp = "sha-2"
        gates.enforced = true
        assertEquals(HooksCheck(30, true), rules.check(ORG, "acme/api"), "A new package is a new key")
        assertEquals(4, gates.asked.size)
    }

    @Test
    fun withoutAPinnedPackageNothingIsKept() = runTest {
        val gates = CountingGates(true, null)
        val rules = OrgsHooksCheckRules(Orgs(mapOf(ORG to 30)), gates)
        rules.check(ORG, "acme/api")
        gates.enforced = false
        assertEquals(HooksCheck(30, false), rules.check(ORG, "acme/api"))
        assertEquals(2, gates.asked.size)
    }

    @Test
    fun anUnknownOrganizationGetsTheDefaultWaitAndTheCacheNeverGrowsPastItsBound() = runTest {
        val gates = CountingGates(false, "sha-1")
        val rules = OrgsHooksCheckRules(Orgs(emptyMap()), gates, maxEntries = 2)

        assertEquals(HooksCheck(15, false), rules.check(ORG, "a/a"))
        rules.check(ORG, "b/b")
        rules.check(ORG, "c/c") // Over the bound: the cache starts again.
        rules.check(ORG, "c/c")
        assertEquals(3, gates.asked.size, "c/c was kept after the restart")
        rules.check(ORG, "a/a")
        assertEquals(4, gates.asked.size, "a/a was dropped by it")
    }
}
