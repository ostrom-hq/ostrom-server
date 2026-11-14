// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), push/.../teamcode/push/Relay.kt; see NOTICE.
package run.ostrom.server.push

import run.ostrom.core.api.models.PushRelaySendResponse

/**
 * What a request to the relay ends as.
 *
 * @property status The HTTP status.
 * @property body The JSON body.
 * @property retryAfterSeconds The `Retry-After` of a refusal, when there is one.
 */
public data class RelayAnswer(
    val status: Int,
    val body: PushRelaySendResponse,
    val retryAfterSeconds: Long? = null,
)
