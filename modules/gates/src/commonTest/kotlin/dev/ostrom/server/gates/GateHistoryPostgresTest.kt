// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates

import dev.ostrom.server.gates.domain.models.AskedVia
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateMode
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.GateSubject
import dev.ostrom.server.gates.domain.repositories.GateHistory
import dev.ostrom.server.gates.domain.repositories.GatesRepository
import dev.ostrom.server.gates.domain.services.GateReceiptSigner
import dev.ostrom.server.testing.serverTest
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The gates' history ([GateHistory]) on a real PostgreSQL, as the serving role: an organization reads its own recent
 * gates, newest first, at most the limit, and never another organization's.
 */
class GateHistoryPostgresTest {
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val orgB = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    private val at = Instant.parse("2026-10-30T02:00:00Z")

    // The module needs a receipt signer; no receipt is signed here.
    private val signer = module {
        single<GateReceiptSigner> {
            object : GateReceiptSigner {
                override suspend fun sign(gate: Gate): String = "rcpt_${gate.id}"
            }
        }
    }

    private fun gateOf(org: Uuid, cls: String, hoursAgo: Int) = Gate(
        id = Uuid.random(),
        orgId = org,
        key = "k-$cls",
        subject = GateSubject(repo = "o/r", actionClass = cls, command = "git push", scope = "session:s"),
        node = null,
        label = cls,
        mode = GateMode.UNGATED,
        status = GateStatus.PENDING,
        askedBy = listOf("lyes"),
        askedVia = setOf(AskedVia.HOOK),
        sessionIds = listOf("s"),
        shownDigest = "s",
        evidence = emptyList(),
        createdAt = at - hoursAgo.hours,
    )

    @Test
    fun theHistoryReadsTheOrganizationsRecentGatesNewestFirst() = serverTest(listOf(GatesModule), signer) { server ->
        val repository = server.koin.get<GatesRepository>()
        repository.transaction {
            insertGate(gateOf(orgA, "push-main", 1))
            insertGate(gateOf(orgA, "deploy", 2))
            insertGate(gateOf(orgA, "merge", 3).copy(unlinkedAuthor = 42L))
            insertGate(gateOf(orgA, "old", 24 * 31))
        }
        repository.transaction { insertGate(gateOf(orgB, "theirs", 1)) }

        val history = server.koin.get<GateHistory>()
        val since = at - 30.days
        assertEquals(listOf("push-main", "deploy", "merge"), history.recent(orgA, since, 10).map { it.label })
        assertEquals(listOf("push-main", "deploy"), history.recent(orgA, since, 2).map { it.label }, "At most limit")
        assertEquals(listOf("theirs"), history.recent(orgB, since, 10).map { it.label }, "Org B reads only its own")
        // The author of a pull request linked to nobody is kept with the gate (Q-GATES-G1).
        assertEquals(listOf(null, null, 42L), history.recent(orgA, since, 10).map { it.unlinkedAuthor })
    }
}
