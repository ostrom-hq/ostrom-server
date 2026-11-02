// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler

import dev.ostrom.server.compiler.domain.models.ActionClass
import dev.ostrom.server.compiler.domain.models.ApproverGate
import dev.ostrom.server.compiler.domain.models.ApproverLine
import dev.ostrom.server.compiler.domain.models.CatalogEntry
import dev.ostrom.server.compiler.domain.models.CatalogGate
import dev.ostrom.server.compiler.domain.usecases.ApproversGraphUseCaseImpl
import dev.ostrom.server.compiler.domain.usecases.GatesCatalogUseCase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** Who approves what: every approver with the gates they answer and the actions each gate covers. */
class ApproversGraphUseCaseTest {
    private val org = Uuid.parse("0000000a-0000-0000-0000-000000000000")

    private fun action(cls: String) = ActionClass(cls, cls, emptyList(), emptyList(), emptyList())

    private fun gate(node: String, approvers: List<String>, enforce: Boolean = true) =
        CatalogGate("w-$node", "Workflow $node", node, "Gate $node", approvers, "accepted", enforce, "declared", "")

    @Test
    fun everyApproverSeesTheGatesTheyAnswerAndTheActionsEachCovers() = runTest {
        val main = gate("main", listOf("lead", "rev-b"))
        val deploy = gate("deploy", listOf("lead"), enforce = false)
        val orphan = gate("orphan", emptyList())
        val entries = listOf(
            CatalogEntry(action("push-main"), main),
            CatalogEntry(action("merge"), main),
            CatalogEntry(action("deploy"), deploy),
            CatalogEntry(action("delete-db"), orphan),
            CatalogEntry(action("commit"), null),
        )
        val asked = mutableListOf<String?>()
        val catalog = object : GatesCatalogUseCase {
            override suspend fun invoke(orgId: Uuid, repo: String?): List<CatalogEntry> {
                asked += repo
                return entries
            }
        }
        val graph = ApproversGraphUseCaseImpl(catalog)(org, "acme/api")
        val mainGate = ApproverGate("w-main", "Workflow main", "main", "Gate main", true, listOf("merge", "push-main"))
        val deployGate = ApproverGate("w-deploy", "Workflow deploy", "deploy", "Gate deploy", false, listOf("deploy"))
        assertEquals(
            listOf(ApproverLine("lead", listOf(deployGate, mainGate)), ApproverLine("rev-b", listOf(mainGate))),
            graph.approvers,
            "approvers by name, their gates by workflow then node, actions by class",
        )
        assertEquals(
            listOf(ApproverGate("w-orphan", "Workflow orphan", "orphan", "Gate orphan", true, listOf("delete-db"))),
            graph.unanswered,
            "a gate nobody answers: its action can never run",
        )
        assertEquals(listOf("commit"), graph.ungated)
        assertEquals(listOf<String?>("acme/api"), asked)
    }

    @Test
    fun anEmptyCatalogueHasNobody() = runTest {
        val catalog = object : GatesCatalogUseCase {
            override suspend fun invoke(orgId: Uuid, repo: String?): List<CatalogEntry> = emptyList()
        }
        val graph = ApproversGraphUseCaseImpl(catalog)(org, null)
        val nothing = Triple(graph.approvers, graph.unanswered, graph.ungated)
        assertEquals(Triple(emptyList(), emptyList(), emptyList()), nothing)
    }
}
