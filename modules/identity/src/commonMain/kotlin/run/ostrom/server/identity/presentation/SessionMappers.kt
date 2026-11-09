// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.identity.presentation

import run.ostrom.server.identity.domain.WebSession
import kotlin.uuid.Uuid

/**
 * Maps a browser session.
 *
 * @param currentId The session making the request.
 */
fun WebSession.toAuthSessionResponse(currentId: Uuid) =
    AuthSessionResponse(id.toString(), createdAt.toString(), lastSeenAt.toString(), current = id == currentId)
