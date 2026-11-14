// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.domain

/**
 * The network edge of the webhooks. An implementation refuses, also after DNS, the hosts [WebhookTargets] refuses,
 * connects to the address it checked, follows no redirect and never reads the answer's body.
 */
fun interface WebhookSender {
    /**
     * POSTs one delivery.
     *
     * @param url The target.
     * @param headers The headers.
     * @param body The body.
     *
     * @return The status, or a short error code.
     */
    suspend fun send(url: String, headers: Map<String, String>, body: String): SendResult
}
