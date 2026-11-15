// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.presentation

import run.ostrom.server.identity.domain.AuthSession
import kotlin.uuid.Uuid

/**
 * Maps one of the person's sign-ins.
 *
 * @param currentId The session or token making the request.
 */
fun AuthSession.toAuthSessionResponse(currentId: Uuid) = AuthSessionResponse(
    id = id.toString(),
    createdAt = createdAt.toString(),
    lastSeenAt = lastSeenAt.toString(),
    current = id == currentId,
    name = name,
    deviceId = deviceId?.toString(),
)
