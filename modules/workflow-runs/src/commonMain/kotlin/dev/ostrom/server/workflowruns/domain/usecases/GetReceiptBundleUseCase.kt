// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.domain.usecases

import dev.ostrom.core.api.models.PackageEnvelope
import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.receipts.domain.models.ReceiptKey
import dev.ostrom.server.receipts.domain.models.ReceiptNotFoundException
import dev.ostrom.server.receipts.domain.repositories.ReceiptsRepository
import dev.ostrom.server.receipts.domain.usecases.ListReceiptKeysUseCase
import dev.ostrom.server.receipts.domain.usecases.isoMillis
import dev.ostrom.server.sessions.domain.models.Session
import dev.ostrom.server.sessions.domain.models.SessionAccess
import dev.ostrom.server.workflowruns.domain.services.RunPackages
import dev.ostrom.server.workflowruns.domain.services.RunSessions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * A receipt with every signed piece it cites (`ostrom.receipt-bundle/1`), verifiable offline by `ostrom verify`.
 *
 * @property receipt The receipt, as signed.
 * @property chain The receipts it cites: its nodes' receipts, its session's, its gates'.
 * @property envelope The signed envelope of the package it reports on, or null.
 * @property keys The instance's public keys, indicative only: `ostrom verify` trusts the keys pinned on the machine.
 * @property exportedAt When the bundle was made.
 */
data class ReceiptBundle(
    val receipt: JsonObject,
    val chain: List<JsonObject>,
    val envelope: PackageEnvelope?,
    val keys: List<ReceiptKey>,
    val exportedAt: String,
)

/** Gathers a receipt's bundle (contract `getReceiptBundle`, prototype `receipt-bundle.ts`): nothing new is signed. */
interface GetReceiptBundleUseCase {
    /**
     * Gathers the bundle.
     *
     * @param actor The caller: a person who reads receipts, or the agent token of the receipt's session's launch.
     * @param id The receipt.
     *
     * @return The bundle.
     *
     * @throws ReceiptNotFoundException When the receipt is unknown, or not one the caller may read.
     */
    suspend operator fun invoke(actor: GateActor, id: String): ReceiptBundle
}

/**
 * [GetReceiptBundleUseCase] on the receipts, sessions and compiler modules.
 *
 * @param receipts The receipts.
 * @param keys The instance's keys.
 * @param sessions The sessions and their gates.
 * @param packages The packages.
 * @param clock The clock.
 */
class GetReceiptBundleUseCaseImpl(
    private val receipts: ReceiptsRepository,
    private val keys: ListReceiptKeysUseCase,
    private val sessions: RunSessions,
    private val packages: RunPackages,
    private val clock: Clock,
) : GetReceiptBundleUseCase {
    override suspend fun invoke(actor: GateActor, id: String): ReceiptBundle {
        val orgId = actor.orgId
        val receipt = receipts.get(orgId, id)?.body ?: throw ReceiptNotFoundException()
        val subject = receipt["subject"] as? JsonObject
        val session = sessionOf(orgId, subject?.text("kind"), subject?.text("id"))
        // An agent token reads the receipts of its own launch's sessions only, answered as absent otherwise.
        val readable = actor.kind != ActorKind.AGENT_SESSION || session != null && SessionAccess.canRead(actor, session)
        if (!readable) throw ReceiptNotFoundException()
        val cited = linkedSetOf<String>()
        (receipt["nodes"] as? JsonArray).orEmpty().mapNotNullTo(cited) { node ->
            (node as? JsonObject)?.text("ref")?.takeIf { it.startsWith("rcpt_") }
        }
        if (session != null) {
            session.receiptId?.let(cited::add)
            sessions.gates(orgId, session.id).mapNotNullTo(cited) { it.receiptId }
        }
        cited -= id
        val chain = cited.mapNotNull { receipts.get(orgId, it)?.body }
        val digest = (receipt["package"] as? JsonObject)?.text("digest")
        val envelope = digest?.let { packages.find(orgId, it)?.envelope?.copy(pinned = false) }
        return ReceiptBundle(receipt, chain, envelope, keys(), clock.now().isoMillis())
    }

    // The session a receipt is about: its own for a session receipt, the one before `@version` for a workflow receipt.
    private suspend fun sessionOf(orgId: Uuid, kind: String?, subjectId: String?): Session? {
        val sessionId = when (kind) {
            "session" -> subjectId
            "workflow" -> subjectId?.substringBeforeLast('@')
            else -> null
        }
        return sessionId?.let { sessions.find(orgId, it) }
    }

    private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
