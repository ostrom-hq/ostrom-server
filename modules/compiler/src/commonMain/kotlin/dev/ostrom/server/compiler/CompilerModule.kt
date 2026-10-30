// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler

import dev.ostrom.server.kernel.module.ServerModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The compiler module: compilation of workflows into signed packages.
 *
 * Empty skeleton registered in the app so that its owner only edits this directory (docs/MODULES.md).
 */
object CompilerModule : ServerModule {
    override val name: String = "compiler"

    override val koinModule: Module = module {}
}
