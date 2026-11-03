// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.receipts.infrastructure

import dev.ostrom.core.receipts.CanonicalJson
import dev.ostrom.core.receipts.Digests
import dev.ostrom.core.receipts.SignatureField
import dev.ostrom.server.instance.domain.ListSigningKeysUseCase
import dev.ostrom.server.kernel.signing.InstanceSigner
import dev.ostrom.server.receipts.domain.models.ReceiptKey
import dev.ostrom.server.receipts.domain.models.ReceiptKeyRotation
import dev.ostrom.server.receipts.domain.services.ReceiptBodySigner
import dev.ostrom.server.receipts.domain.services.TrustedReceiptKeys
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Signs receipts with the instance key, byte for byte as the prototype (`mvp/server/src/receipts.ts`): the canonical
 * form of ostrom-core, its SHA-256, an Ed25519 signature over the canonical bytes, and the field
 * `ed25519;kid=<kid>;sha256=<hex>;sig=<base64>`.
 *
 * @param signer The instance key (its kid is the prototype's: 16 hex characters of the SHA-256 of the SPKI DER).
 */
class InstanceReceiptSigner(private val signer: InstanceSigner) : ReceiptBodySigner {
    override suspend fun sign(body: JsonObject): JsonObject {
        require(SIGNATURE !in body) { "The body to sign already has a signature." }
        val bytes = CanonicalJson.bytes(body)
        val field = SignatureField(SignatureField.ED25519, signer.keyId, Digests.sha256Hex(bytes), signer.sign(bytes))
        return JsonObject(body + (SIGNATURE to JsonPrimitive(field.toString())))
    }

    private companion object {
        const val SIGNATURE = "signature"
    }
}

/**
 * The instance keys, as the instance module records them at each start.
 *
 * @param listSigningKeys The instance module's key list.
 */
class InstanceTrustedReceiptKeys(private val listSigningKeys: ListSigningKeysUseCase) : TrustedReceiptKeys {
    override suspend fun keys(): List<ReceiptKey> =
        listSigningKeys().map { key ->
            val rotation = key.rotation?.let { ReceiptKeyRotation(it.keyId, it.signature) }
            ReceiptKey(key.keyId, key.algorithm, key.publicKeyPem, rotation)
        }
}
