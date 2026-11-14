// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), push/.../teamcode/push/Relay.kt; see NOTICE.
package run.ostrom.server.push

import run.ostrom.core.api.models.PushRelaySendRequest
import run.ostrom.core.api.models.PushRelaySendResponse

/** The provider edge: one message to FCM, answered in the relay's own terms. Never logs the token or the payload. */
public fun interface FcmSender {
    /**
     * Sends one push.
     *
     * @param request The validated request.
     *
     * @return What the provider answered, reduced to a status and a short code.
     */
    public suspend fun send(request: PushRelaySendRequest): PushRelaySendResponse
}
