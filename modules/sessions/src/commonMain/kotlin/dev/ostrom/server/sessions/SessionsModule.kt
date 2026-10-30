package dev.ostrom.server.sessions

import dev.ostrom.server.kernel.module.ServerModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The sessions module: agent sessions: their lifecycle, transcripts and events.
 *
 * Empty skeleton registered in the app so that its owner only edits this directory (docs/MODULES.md).
 */
object SessionsModule : ServerModule {
    override val name: String = "sessions"

    override val koinModule: Module = module {}
}
