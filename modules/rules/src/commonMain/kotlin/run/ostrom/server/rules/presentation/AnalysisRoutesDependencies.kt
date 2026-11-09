// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.rules.presentation

import run.ostrom.server.gates.domain.services.GateActorResolver
import run.ostrom.server.rules.domain.usecases.GetAnalysisStateUseCase
import run.ostrom.server.rules.domain.usecases.GetReplayUseCase
import run.ostrom.server.rules.domain.usecases.StartAnalysisUseCase
import run.ostrom.server.secrets.domain.CallerResolver

/**
 * What the analysis routes need.
 *
 * @property callers Resolves the caller's organization and role.
 * @property actors Names the person who starts a run.
 * @property start Starts an analysis or a replay.
 * @property state Reads the snapshot.
 * @property replay Reads the last replay.
 */
data class AnalysisRoutesDependencies(
    val callers: CallerResolver,
    val actors: GateActorResolver,
    val start: StartAnalysisUseCase,
    val state: GetAnalysisStateUseCase,
    val replay: GetReplayUseCase,
)
