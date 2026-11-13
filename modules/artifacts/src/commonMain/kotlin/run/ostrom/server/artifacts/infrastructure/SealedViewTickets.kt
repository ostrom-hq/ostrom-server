// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts.infrastructure

import run.ostrom.server.artifacts.domain.ViewTicket
import run.ostrom.server.artifacts.domain.ViewTicketSeal
import run.ostrom.server.kernel.signing.InstanceSigner
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/**
 * [ViewTicketSeal] as TeamCode's (`ArtifactViewing.kt`, `ArtifactTickets`): a ticket is `<ciphertext>.<mac>`, both
 * base64url without padding. The payload `<version>:<member>:<subject>:<credential>:<expiry>` is authenticated by an
 * HMAC-SHA256 over the artifact id and the payload, then encrypted with AES-CTR whose IV is the MAC's first 16 bytes
 * (a synthetic IV: the same ticket always gives the same address, and nothing is learned beyond equality).
 *
 * Both keys are derived by HKDF-SHA256 (RFC 5869, zero salt, one block) under their own labels from a seed nothing
 * stored gives: the instance's Ed25519 signature (deterministic, RFC 8032) of [SEED_LABEL], which no other signed
 * message of the instance starts with. A database copy alone never opens or mints a ticket; replacing the instance
 * key ends every ticket, which a reload mints again.
 *
 * @param signer The instance key.
 */
internal class SealedViewTickets(private val signer: InstanceSigner) : ViewTicketSeal {
    private var keys: Keys? = null

    override suspend fun seal(ticket: ViewTicket): String {
        val fields = listOf(ticket.memberId, ticket.subjectId, ticket.credentialId)
        require(ticket.version >= 1 && fields.none { it.isEmpty() || ':' in it }) {
            "A ticket names a version from 1, and its fields are non-empty and never hold a colon."
        }
        val payload = "${ticket.version}:${fields.joinToString(":")}:${ticket.expiresAt}"
        val mac = mac(ticket.artifactId, payload)
        return "${URL.encode(crypt(mac, payload.encodeToByteArray()))}.${URL.encode(mac)}"
    }

    override suspend fun open(artifactId: Uuid, sealed: String): ViewTicket? {
        val (ciphertext, presented) = canonical(sealed) ?: return null
        val payload = crypt(presented, ciphertext).decodeToString()
        return if (constantTimeEquals(presented, mac(artifactId, payload))) ticketOf(artifactId, payload) else null
    }

    /** The ciphertext and the MAC of a ticket of the right shape, in its one canonical spelling; null otherwise. */
    private fun canonical(sealed: String): List<ByteArray>? {
        val shaped = sealed.length <= MAX_LENGTH && SHAPE.matches(sealed)
        val parts = if (shaped) sealed.split('.').mapNotNull { decoded(it) } else emptyList()
        // Only the canonical spelling: one ticket is one string, cached and compared as such.
        return parts.takeIf { it.size == 2 && it.joinToString(".") { part -> URL.encode(part) } == sealed }
    }

    /** An authenticated payload: [seal] wrote it, so it has its five fields. */
    private fun ticketOf(artifactId: Uuid, payload: String): ViewTicket {
        val fields = payload.split(':')
        val (member, subject, credential) = fields.subList(1, fields.size - 1)
        return ViewTicket(artifactId, fields.first().toInt(), member, subject, credential, fields.last().toLong())
    }

    private suspend fun keys(): Keys = keys ?: run {
        val seed = signer.sign(SEED_LABEL.encodeToByteArray())
        Keys(hkdf(seed, MAC_LABEL), hkdf(seed, ENCRYPTION_LABEL)).also { keys = it }
    }

    private suspend fun mac(artifactId: Uuid, payload: String): ByteArray =
        hmac(keys().mac, "$artifactId\n$payload".encodeToByteArray())

    @OptIn(DelicateCryptographyApi::class) // A synthetic IV: the MAC of the payload, unique per payload.
    private suspend fun crypt(mac: ByteArray, input: ByteArray): ByteArray =
        CryptographyProvider.Default.get(AES.CTR).keyDecoder()
            .decodeFromByteArray(AES.Key.Format.RAW, keys().encryption)
            .cipher()
            .encryptWithIv(mac.copyOf(IV_BYTES), input)

    private class Keys(val mac: ByteArray, val encryption: ByteArray)

    /** The derivation and the ticket's shape. */
    companion object {
        /** The label the instance signs for the seed of the keys. */
        const val SEED_LABEL = "ostrom/artifacts/view-ticket/v1"
        private const val MAC_LABEL = "ostrom artifact-view"
        private const val ENCRYPTION_LABEL = "ostrom artifact-view encryption"
        private const val IV_BYTES = 16
        private const val MAX_LENGTH = 500
        private val SHAPE = Regex("[A-Za-z0-9_-]{4,450}\\.[A-Za-z0-9_-]{43}")
        private val URL = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

        /**
         * HKDF-SHA256 (RFC 5869) with an all-zero salt and one 32-byte block of output.
         *
         * @param input The input keying material.
         * @param label The `info`.
         *
         * @return The derived key.
         */
        internal suspend fun hkdf(input: ByteArray, label: String): ByteArray {
            val prk = hmac(ByteArray(HASH_BYTES), input)
            return hmac(prk, label.encodeToByteArray() + byteArrayOf(1))
        }

        private const val HASH_BYTES = 32

        private suspend fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            CryptographyProvider.Default.get(HMAC).keyDecoder(SHA256)
                .decodeFromByteArray(HMAC.Key.Format.RAW, key)
                .signatureGenerator()
                .generateSignature(data)

        private fun decoded(text: String): ByteArray? = try {
            URL.decode(text)
        } catch (_: IllegalArgumentException) {
            null
        }

        // Both are 32 bytes: the shape fixes the presented MAC's length.
        private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
            a.indices.fold(0) { difference, index -> difference or (a[index].toInt() xor b[index].toInt()) } == 0
    }
}
