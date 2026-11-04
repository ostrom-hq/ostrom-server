// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.receipts

import dev.ostrom.core.receipts.ReceiptSigningKey
import dev.ostrom.server.kernel.signing.InstanceSigner
import dev.ostrom.server.receipts.domain.models.ReceiptKey
import dev.ostrom.server.receipts.domain.models.ReceiptQuery
import dev.ostrom.server.receipts.domain.models.StoredReceipt
import dev.ostrom.server.receipts.domain.repositories.ReceiptsRepository
import dev.ostrom.server.receipts.domain.services.TrustedReceiptKeys
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
        val newest = rows.filter { it.orgId == orgId }.reversed()
        val start = query.before?.let { before -> newest.indexOfFirst { it.id == before } + 1 } ?: 0
        return if (start == 0 && query.before != null) emptyList() else newest.drop(start).take(query.limit)
    }
}

/** A gate receipt body without its id, date and signature. */
internal fun draft(subjectId: String = "gate-1", kind: String = "gate"): JsonObject = buildJsonObject {
    putJsonObject("subject") {
        put("kind", kind)
        put("id", subjectId)
        put("digest", "d".repeat(64))
    }
    put("initiator", "dev-a")
    put("agent", "claude-code")
    putJsonArray("rules") {}
    putJsonArray("decisions") {}
}

internal fun JsonObject.text(name: String): String = (getValue(name) as JsonPrimitive).content
