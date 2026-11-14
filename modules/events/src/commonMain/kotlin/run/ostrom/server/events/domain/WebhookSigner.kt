// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.domain

/** Signs a delivery as its receiver checks it, and makes webhook secrets. */
interface WebhookSigner {
    /**
     * `Ostrom-Signature`: `v1=` and the hex HMAC-SHA256 of `<timestamp>.<body>`, keyed with the whole secret.
     *
     * @param secret The webhook's secret.
     * @param timestampSeconds The `Ostrom-Timestamp`.
     * @param body The body.
     *
     * @return The header's value.
     */
    suspend fun signature(secret: String, timestampSeconds: Long, body: String): String

    /**
     * A new secret: `whsec_` and 32 random bytes, base64url without padding.
     *
     * @return The secret.
     */
    fun newSecret(): String
}
