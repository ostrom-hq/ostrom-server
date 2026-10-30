// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs

import dev.ostrom.server.kernel.module.ServerModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The orgs module: organizations, teams, memberships and roles.
 *
 * Empty skeleton registered in the app so that its owner only edits this directory (docs/MODULES.md).
 */
object OrgsModule : ServerModule {
    override val name: String = "orgs"

    override val koinModule: Module = module {}
}
