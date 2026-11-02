// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches

import dev.ostrom.server.gates.GatesModule
import dev.ostrom.server.identity.IdentityModule
import dev.ostrom.server.instance.InstanceModule
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.orgs.OrgsModule
import dev.ostrom.server.receipts.ReceiptsModule
import dev.ostrom.server.runners.RunnersModule
import dev.ostrom.server.sessions.SessionsModule

/**
 * The modules every launch test loads before its own: what the launches module's routes need to start (the runner's
 * launch route checks runner tokens, so the runners module is one of them).
 *
 * @param more The test's own modules, loaded after.
 *
 * @return The modules, launches included.
 */
fun launchModules(vararg more: ServerModule): List<ServerModule> = listOf(
    InstanceModule, IdentityModule, OrgsModule, ReceiptsModule, GatesModule, SessionsModule, RunnersModule,
    LaunchesModule,
) + more
