// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.receipts

import run.ostrom.core.receipts.ReceiptSigningKey
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.signing.InstanceSigner
import run.ostrom.server.receipts.domain.models.ReceiptKey
import run.ostrom.server.receipts.domain.models.ReceiptQuery
import run.ostrom.server.receipts.domain.models.ReceiptReader
import run.ostrom.server.receipts.domain.models.StoredReceipt
import run.ostrom.server.receipts.domain.repositories.ReceiptsRepository
import run.ostrom.server.receipts.domain.services.ReceiptOwners
import run.ostrom.server.receipts.domain.services.ReceiptReaders
import run.ostrom.server.receipts.domain.services.TrustedReceiptKeys
import run.ostrom.server.receipts.domain.usecases.ReceiptAccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal val ORG_A: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000000")
internal val ORG_B: Uuid = Uuid.parse("0000000b-0000-0000-0000-000000000000")

/** The instance signer, with a key from a seed (deterministic, as Ed25519 is). */
internal class SeedSigner(seed: ByteArray) : InstanceSigner {
    private val key = ReceiptSigningKey.fromSeed(seed)
    override val algorithm: String = "ed25519"
    override val keyId: String = key.publicKey.kid
    override val publicKeyPem: String = key.publicKey.toSpkiPem()
    override suspend fun sign(data: ByteArray): ByteArray = key.sign(data)
    override suspend fun verify(data: ByteArray, signature: ByteArray): Boolean = key.publicKey.verify(data, signature)

    fun receiptKey() = ReceiptKey(keyId, algorithm, publicKeyPem)
}

internal fun hex(text: String): ByteArray =
    ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }


internal class FixedClock(var now: Instant = Instant.parse("2026-10-30T09:12:34.567Z")) : Clock {
    override fun now(): Instant = now
}

internal class FakeKeys(var keys: List<ReceiptKey>) : TrustedReceiptKeys {
    override suspend fun keys(): List<ReceiptKey> = keys
}

internal class FakeReceipts : ReceiptsRepository {
    val rows = mutableListOf<StoredReceipt>()
    var lastQuery: ReceiptQuery? = null

    override suspend fun insertIfAbsent(receipt: StoredReceipt): StoredReceipt =
        get(receipt.orgId, receipt.id) ?: receipt.also { rows += it }

    override suspend fun get(orgId: Uuid, id: String): StoredReceipt? =
        rows.firstOrNull { it.orgId == orgId && it.id == id }

    override suspend fun list(orgId: Uuid, query: ReceiptQuery): List<StoredReceipt> {
        lastQuery = query
        // As the database: after `before`, older receipts only; none after a receipt it does not hold.
        val audience = query.audience
        val newest = rows.filter { it.orgId == orgId }.reversed().filter {
            audience == null || it.sessionId == null || it.member == audience.member && it.member != null ||
                it.sessionId in audience.sessions
        }
        val start = query.before?.let { before -> newest.indexOfFirst { it.id == before } + 1 } ?: 0
        return if (start == 0 && query.before != null) emptyList() else newest.drop(start).take(query.limit)
    }
}

/** The readers: [approvals] maps a person to the sessions whose gates they approve; [readers] resolves tokens. */
internal class FakeReaders(
    private val approvals: Map<String, List<String>> = emptyMap(),
    private val readers: Map<String, ReceiptReader> = emptyMap(),
) : ReceiptReaders {
    val asked = mutableListOf<String>()

    override suspend fun resolve(principal: AuthenticatedPrincipal): ReceiptReader? = readers[principal.subjectId]

    override suspend fun approves(orgId: Uuid, person: String, sessionId: String): Boolean {
        asked += "approves $person $sessionId"
        return sessionId in approvals[person].orEmpty()
    }

    override suspend fun approvedSessions(orgId: Uuid, person: String): List<String> {
        asked += "sessions $person"
        return approvals[person].orEmpty()
    }
}

/** An owner or an administrator of [org], who reads every receipt. */
internal fun admin(org: Uuid): ReceiptReader = ReceiptReader(org, "root", readsAll = true)

/** Who reads the receipts of [receipts], [readers] naming the approvers. */
internal fun access(receipts: ReceiptsRepository, readers: ReceiptReaders = FakeReaders()) =
    ReceiptAccess(receipts, readers)

/** The person of each session. */
internal class FakeOwners(private val owners: Map<String, String> = emptyMap()) : ReceiptOwners {
    override suspend fun ownerOf(orgId: Uuid, sessionId: String): String? = owners[sessionId]
}

/** A gate receipt body without its id, date and signature. */
internal fun draft(subjectId: String = "gate-1", kind: String = "gate", session: String? = null): JsonObject =
    buildJsonObject {
        putJsonObject("subject") {
            put("kind", kind)
            put("id", subjectId)
            put("digest", "d".repeat(64))
        }
        session?.let { put("sessionId", it) }
        put("initiator", "dev-a")
        put("agent", "claude-code")
        putJsonArray("rules") {}
        putJsonArray("decisions") {}
    }

internal fun JsonObject.text(name: String): String = (getValue(name) as JsonPrimitive).content
