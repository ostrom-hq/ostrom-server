// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sessions.presentation

import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.sessions.domain.usecases.EndSessionUseCase
import dev.ostrom.server.sessions.domain.usecases.GetSessionUseCase
import dev.ostrom.server.sessions.domain.usecases.ListSessionsUseCase
import dev.ostrom.server.sessions.domain.usecases.ReopenSessionUseCase
import dev.ostrom.server.sessions.domain.usecases.ReportSeenUseCase
import dev.ostrom.server.sessions.domain.usecases.SessionAliveUseCase
import dev.ostrom.server.sessions.domain.usecases.StartSessionUseCase

/**
 * What the sessions routes need.
 *
 * @property actors Resolves callers.
 * @property start Starts a segment.
 * @property end Ends one.
 * @property alive Records a sign of life.
 * @property reopen Reopens after a closed one.
 * @property seen Records a skill or sub-agent opened.
 * @property get Reads one.
 * @property list Lists them.
 */
data class SessionsRoutesDependencies(
    val actors: GateActorResolver,
    val start: StartSessionUseCase,
    val end: EndSessionUseCase,
    val alive: SessionAliveUseCase,
    val reopen: ReopenSessionUseCase,
    val seen: ReportSeenUseCase,
    val get: GetSessionUseCase,
    val list: ListSessionsUseCase,
)
