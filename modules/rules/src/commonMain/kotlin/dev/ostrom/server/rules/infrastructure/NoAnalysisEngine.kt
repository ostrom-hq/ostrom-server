// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.infrastructure

import dev.ostrom.server.rules.domain.models.AnalysisRun
import dev.ostrom.server.rules.domain.services.AnalysisEngine
import dev.ostrom.server.rules.domain.services.AnalysisStart

/**
 * The engine while none is installed: every run fails at once, visibly, with a reason the console shows and a retry
 * path. Nothing pretends to analyse.
 */
class NoAnalysisEngine : AnalysisEngine {
    override suspend fun start(run: AnalysisRun): AnalysisStart =
        AnalysisStart.Unavailable(STAGE, "No analysis engine runs on this server yet.")

    private companion object {
        const val STAGE = "engine"
    }
}
