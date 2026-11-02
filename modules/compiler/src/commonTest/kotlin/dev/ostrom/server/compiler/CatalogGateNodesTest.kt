// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler

import dev.ostrom.server.compiler.domain.models.ActionClass
import dev.ostrom.server.compiler.domain.models.CatalogEntry
import dev.ostrom.server.compiler.domain.models.CatalogGate
import dev.ostrom.server.compiler.domain.usecases.GatesCatalogUseCase
import dev.ostrom.server.compiler.infrastructure.CatalogGateNodes
import dev.ostrom.server.gates.domain.models.GateNode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
}
