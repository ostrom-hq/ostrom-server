// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations

import dev.ostrom.server.kernel.module.ServerModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The automations module: automations, schedules and waits.
 *
 * Empty skeleton registered in the app so that its owner only edits this directory (docs/MODULES.md).
 */
object AutomationsModule : ServerModule {
    override val name: String = "automations"

    override val koinModule: Module = module {}
}
