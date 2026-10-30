// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sharing

import dev.ostrom.server.kernel.module.ServerModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The sharing module: sharing of personal subscriptions within a team.
 *
 * Empty skeleton registered in the app so that its owner only edits this directory (docs/MODULES.md).
 */
object SharingModule : ServerModule {
    override val name: String = "sharing"

    override val koinModule: Module = module {}
}
