// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners

import dev.ostrom.server.kernel.module.ServerModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The runners module: cloud runners and devices that execute agents.
 *
 * Empty skeleton registered in the app so that its owner only edits this directory (docs/MODULES.md).
 */
object RunnersModule : ServerModule {
    override val name: String = "runners"

    override val koinModule: Module = module {}
}
