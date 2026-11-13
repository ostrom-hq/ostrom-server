// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sessions.presentation

import run.ostrom.server.gates.domain.services.GateActorResolver
import run.ostrom.server.sessions.domain.usecases.EndSessionUseCase
import run.ostrom.server.sessions.domain.usecases.GetSessionUseCase
import run.ostrom.server.sessions.domain.usecases.ListSessionsUseCase
import run.ostrom.server.sessions.domain.usecases.ReopenSessionUseCase
import run.ostrom.server.sessions.domain.usecases.ReportSeenUseCase
import run.ostrom.server.sessions.domain.usecases.SessionAliveUseCase
import run.ostrom.server.sessions.domain.usecases.StartSessionUseCase

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
