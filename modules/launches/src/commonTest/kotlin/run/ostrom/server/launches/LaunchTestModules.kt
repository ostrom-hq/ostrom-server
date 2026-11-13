// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.launches

import run.ostrom.server.gates.GatesModule
import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.orgs.OrgsModule
import run.ostrom.server.receipts.ReceiptsModule
import run.ostrom.server.runners.RunnersModule
import run.ostrom.server.sessions.SessionsModule

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
