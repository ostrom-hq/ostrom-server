// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory

import run.ostrom.server.memory.domain.CurationCandidate
import run.ostrom.server.memory.domain.CurationDecision
import run.ostrom.server.memory.domain.CurationReport
import run.ostrom.server.memory.domain.CurationTitle
import run.ostrom.server.memory.domain.MemoryCurator
import run.ostrom.server.memory.domain.MemoryOrigin
import run.ostrom.server.memory.domain.ReviewKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** The model-free pass of TeamCode's curation: groups of equal titles, the newest wins when it is safe. */
class MemoryCuratorTest {
    private val ids = List(30) { Uuid.random() }

    private fun candidate(
        index: Int,
        content: String = "Connect to the VPN first.",
        origin: MemoryOrigin = MemoryOrigin.HUMAN,
        external: Boolean = false,
    ) = CurationCandidate(ids[index], content, origin, external)

    @Test
    fun caseAndRunsOfWhitespaceDoNotMakeTwoTextsDifferent() {
        assertEquals("deploy needs a vpn", MemoryCurator.normalize("  Deploy   needs\ta VPN "))
    }

    @Test
    fun entriesOfOneTitleFormAGroupNewestFirstWithinItsBounds() {
        val titles = listOf(
            CurationTitle(ids[0], "Deploy needs a VPN"),
            CurationTitle(ids[1], "Alone"),
            CurationTitle(ids[2], "deploy  needs a vpn"),
        )
        assertEquals(listOf(listOf(ids[0], ids[2])), MemoryCurator.groups(titles))
        // At most 20 groups a scope, 8 entries a group.
        val many = (0 until 25).flatMap { group -> List(2) { CurationTitle(Uuid.random(), "Title $group") } }
        assertEquals(20, MemoryCurator.groups(many).size)
        val big = List(10) { CurationTitle(ids[it], "Same") }
        assertEquals(ids.take(8), MemoryCurator.groups(big).single())
    }

    @Test
    fun theNewestWinsOverEqualTextAndAPersonDecidesEverythingElse() {
        val decisions = MemoryCurator.decide(
            listOf(
                candidate(0, "connect to the   VPN first.", MemoryOrigin.AGENT),
                candidate(1, origin = MemoryOrigin.AGENT),
                candidate(2, "It moved."),
                candidate(3),
                candidate(4, origin = MemoryOrigin.AGENT, external = true),
            ),
        )
        assertEquals(CurationDecision.Supersede(ids[1], ids[0]), decisions[0])
        assertEquals(ReviewKind.SAME_TITLE, (decisions[1] as CurationDecision.Queue).kind)
        assertEquals(ids[2] to ids[0], decisions[1].olderId to decisions[1].newerId)
        val overPerson = decisions[2] as CurationDecision.Queue
        assertEquals(ReviewKind.DUPLICATE to "Same text; a person wrote the older one and an agent the newer one.",
            overPerson.kind to overPerson.reason)
        assertEquals("Same text; one comes from outside (tagged external).",
            (decisions[3] as CurationDecision.Queue).reason)
        // The newest from outside holds even a same-origin older one.
        val outside = MemoryCurator.decide(listOf(candidate(0, external = true), candidate(1)))
        assertEquals(ReviewKind.DUPLICATE, (outside.single() as CurationDecision.Queue).kind)
    }

    @Test
    fun twoRunsAddUp() {
        assertEquals(CurationReport(3, 5, 7, 9), CurationReport(1, 2, 3, 4) + CurationReport(2, 3, 4, 5))
    }
}
