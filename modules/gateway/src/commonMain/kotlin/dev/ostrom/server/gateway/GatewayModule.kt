// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway

import dev.ostrom.server.kernel.module.ServerModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The gateway module: the inference gateway: providers, routing and usage.
 *
 * Empty skeleton registered in the app so that its owner only edits this directory (docs/MODULES.md).
 */
object GatewayModule : ServerModule {
    override val name: String = "gateway"

    override val koinModule: Module = module {}
}
