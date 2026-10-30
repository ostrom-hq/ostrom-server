// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.slack.infrastructure

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Checks Slack's request signature: `v0=` and the HMAC-SHA256 of `v0:<timestamp>:<body>` with the app's signing
 * secret, compared in constant time, for a timestamp at most 5 minutes from now (a replayed click is refused).
 *
 * @param secret The signing secret, or null: then nothing is ever accepted.
 * @param clock The time.
 */
class SlackSignature(private val secret: String?, private val clock: Clock) {
    /**
     * @param body The raw body, as received.
     * @param timestamp The `X-Slack-Request-Timestamp` header.
     * @param signature The `X-Slack-Signature` header.
     * @return True only for this body, signed with this secret, recently.
     */
    suspend fun verify(body: ByteArray, timestamp: String?, signature: String?): Boolean {
        val seconds = timestamp?.toLongOrNull()
        val fresh = seconds != null && !stale(seconds)
        if (secret == null || signature == null || !fresh) return false
        val key = CryptographyProvider.Default.get(HMAC).keyDecoder(SHA256)
            .decodeFromByteArray(HMAC.Key.Format.RAW, secret.encodeToByteArray())
        val mac = key.signatureGenerator().generateSignature("v0:$timestamp:".encodeToByteArray() + body)
        val expected = ("v0=" + mac.joinToString("") { HEX[it.toInt() and BYTE] }).encodeToByteArray()
        val got = signature.encodeToByteArray()
        var difference = expected.size xor got.size
        for (index in expected.indices) {
            difference = difference or (expected[index].toInt() xor got.getOrElse(index) { 0 }.toInt())
        }
        return difference == 0
    }

    private fun stale(seconds: Long): Boolean =
        (clock.now() - Instant.fromEpochSeconds(seconds)).absoluteValue > MAX_SKEW
}

// Top-level, not in a private companion: a companion property has a getter nothing calls, which Kover counts.
private val MAX_SKEW = 5.minutes
private const val BYTE = 0xff
private val HEX: List<String> = List(256) { it.toString(16).padStart(2, '0') }
