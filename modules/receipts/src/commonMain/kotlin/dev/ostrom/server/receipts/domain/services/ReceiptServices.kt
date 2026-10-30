package dev.ostrom.server.receipts.domain.services

import dev.ostrom.server.receipts.domain.models.ReceiptKey
import kotlinx.serialization.json.JsonObject

/** Signs a receipt body with the instance key, in format v1. */
interface ReceiptBodySigner {
    /**
     * Signs every field of [body], known or not.
     *
     * @param body The receipt without its `signature` field.
     *
     * @return The body with its `signature` field added.
     */
    suspend fun sign(body: JsonObject): JsonObject
}

/** The public keys a receipt of this instance is verified against. */
interface TrustedReceiptKeys {
    /**
     * Lists the keys.
     *
     * @return Every key the instance signs or once signed with, oldest first.
     */
    suspend fun keys(): List<ReceiptKey>
}
