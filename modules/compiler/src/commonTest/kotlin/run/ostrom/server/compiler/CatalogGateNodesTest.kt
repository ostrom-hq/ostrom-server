// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler

import run.ostrom.server.compiler.domain.models.ActionClass
import run.ostrom.server.compiler.domain.models.CatalogEntry
import run.ostrom.server.compiler.domain.models.CatalogGate
import run.ostrom.server.compiler.domain.models.PackagePin
import run.ostrom.server.compiler.domain.usecases.GatesCatalogUseCase
import run.ostrom.server.compiler.infrastructure.CatalogGateNodes
import run.ostrom.server.gates.domain.models.GateNode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The gates of an ask, read from the catalogue: compiled once for every class the ask looks up. */
class CatalogGateNodesTest {
    private val org = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val main =
        CatalogGate("w", "Ship", "g-main", "Push to main", listOf("rev-b"), "accepted", true, "declared", "")
    private val compiled = mutableListOf<String?>()
    private val nodes = CatalogGateNodes {
        object : GatesCatalogUseCase {
            override suspend fun invoke(orgId: Uuid, repo: String?): List<CatalogEntry> {
                compiled += repo
                return listOf(
                    CatalogEntry(ActionClass("push-main", "Push", emptyList(), emptyList(), emptyList()), main),
                    CatalogEntry(ActionClass("git.push", "Any push", emptyList(), emptyList(), emptyList()), null),
                )
            }
        }
    }

    @Test
    fun anAskCompilesTheCatalogueOnceForEveryClassItLooksUp() = runTest {
        val gateOf = nodes.resolver(org, "acme/api")

        val node = GateNode("w", "g-main", "Push to main", listOf("rev-b"), true, "Ship", "accepted", "declared", "")
        assertEquals(node, gateOf("push-main"))
        assertNull(gateOf("git.push"), "A class the catalogue knows without a gate")
        assertNull(gateOf("deploy"), "A class the catalogue does not know")
        assertEquals(listOf<String?>("acme/api"), compiled)
    }

    @Test
    fun oneClassIsResolvedOnItsOwnRead() = runTest {
        assertEquals("g-main", nodes.resolve(org, null, "push-main")?.nodeId)
        assertEquals(listOf<String?>(null), compiled)
    }

    @Test
    fun anEnforcedGateInTheCatalogueMeansTheOrganizationEnforcesOne() = runTest {
        assertEquals(true, nodes.enforcesAny(org, "acme/api"))
        assertEquals(listOf<String?>("acme/api"), compiled, "One read of the catalogue")
    }

    @Test
    fun aCatalogueWithOnlyProposedOrNoGatesEnforcesNone() = runTest {
        val proposed = main.copy(status = "proposed", enforce = false)
        val entry = CatalogEntry(ActionClass("push-main", "Push", emptyList(), emptyList(), emptyList()), proposed)
        val ungated = CatalogEntry(ActionClass("git.push", "Any push", emptyList(), emptyList(), emptyList()), null)
        val of = { entries: List<CatalogEntry> ->
            CatalogGateNodes {
                object : GatesCatalogUseCase {
                    override suspend fun invoke(orgId: Uuid, repo: String?): List<CatalogEntry> = entries
                }
            }
        }
        assertEquals(false, of(listOf(entry, ungated)).enforcesAny(org, null), "Only a proposed gate")
        assertEquals(false, of(listOf(ungated)).enforcesAny(org, null), "A class without a gate")
        assertEquals(false, of(emptyList()).enforcesAny(org, null), "An empty catalogue")
    }

    @Test
    fun theStampOfTheGatesIsTheDigestOfThePinnedPackage() = runTest {
        val pins = MemoryCompilerRepository()
        val withPins = CatalogGateNodes({ pins }) { error("no catalogue read") }
        assertNull(withPins.policyStamp(org, "acme/api"), "No pinned package yet")
        pins.pins[org to "acme/api"] = PackagePin("sha-1", "compiler (auto pin)", Instant.parse("2026-11-03T10:00:00Z"))
        assertEquals("sha-1", withPins.policyStamp(org, "acme/api"))
        assertNull(withPins.policyStamp(org, null), "Another repository")
        assertNull(nodes.policyStamp(org, "acme/api"), "Without a repository of pins")
    }
}
