// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.domain.usecases

import run.ostrom.core.api.models.PackageEnvelope
import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.receipts.domain.models.ReceiptKey
import run.ostrom.server.receipts.domain.models.ReceiptNotFoundException
import run.ostrom.server.receipts.domain.models.ReceiptReader
import run.ostrom.server.receipts.domain.repositories.ReceiptsRepository
import run.ostrom.server.receipts.domain.usecases.ListReceiptKeysUseCase
import run.ostrom.server.receipts.domain.usecases.ReceiptAccess
import run.ostrom.server.receipts.domain.usecases.isoMillis
import run.ostrom.server.sessions.domain.models.Session
import run.ostrom.server.sessions.domain.models.SessionAccess
import run.ostrom.server.workflowruns.domain.services.RunPackages
import run.ostrom.server.workflowruns.domain.services.RunSessions
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
     * @param actor The caller: a person who reads the receipt (its session's person, an owner or an administrator, an
     *   approver of the session's gates), or the agent token of the receipt's session's launch.
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
 * @param access Who reads a receipt: a session's person and its gates' approvers, any reader one about no session.
 * @param keys The instance's keys.
 * @param sessions The sessions and their gates.
 * @param packages The packages.
 * @param clock The clock.
 */
class GetReceiptBundleUseCaseImpl(
    private val receipts: ReceiptsRepository,
    private val access: ReceiptAccess,
    private val keys: ListReceiptKeysUseCase,
    private val sessions: RunSessions,
    private val packages: RunPackages,
    private val clock: Clock,
) : GetReceiptBundleUseCase {
    override suspend fun invoke(actor: GateActor, id: String): ReceiptBundle {
        val orgId = actor.orgId
        val stored = receipts.get(orgId, id) ?: throw ReceiptNotFoundException()
        val receipt = stored.body
        val subject = receipt["subject"] as? JsonObject
        val session = sessionOf(orgId, subject?.text("kind"), subject?.text("id"))
        // An agent token reads the receipts of its own launch's sessions only; a person those of the sessions they
        // read for receipts (TeamCode #238); anyone else is answered as absent.
        val reader = readerOf(actor)
        val readable = if (reader == null) {
            session != null && SessionAccess.readsContent(actor, session)
        } else {
            access.canRead(reader, stored)
        }
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
        // A cited receipt is the same session's, but a node may cite any: the chain keeps those the caller reads.
        val chain = cited.mapNotNull { receipts.get(orgId, it) }
            .filter { reader == null || access.canRead(reader, it) }
            .map { it.body }
        val digest = (receipt["package"] as? JsonObject)?.text("digest")
        val envelope = digest?.let { packages.find(orgId, it) }?.let { it.envelope.copy(pinned = false) }
        return ReceiptBundle(receipt, chain, envelope, keys(), clock.now().isoMillis())
    }

    // A person reads receipts as the receipts routes do; an agent token is no reader of its own.
    private fun readerOf(actor: GateActor): ReceiptReader? = actor.takeIf { it.kind != ActorKind.AGENT_SESSION }
        ?.let { ReceiptReader(it.orgId, it.personId) }

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
