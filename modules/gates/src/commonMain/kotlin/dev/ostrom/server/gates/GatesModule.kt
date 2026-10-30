// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates

import dev.ostrom.server.kernel.module.ServerModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The gates module: gates: the human approvals a workflow step waits for.
 *
 * Empty skeleton registered in the app so that its owner only edits this directory (docs/MODULES.md).
 */
object GatesModule : ServerModule {
    override val name: String = "gates"

    override val koinModule: Module = module {}
}
