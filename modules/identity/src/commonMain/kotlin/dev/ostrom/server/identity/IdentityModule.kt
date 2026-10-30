package dev.ostrom.server.identity

import dev.ostrom.server.kernel.module.ServerModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The identity module: users, devices, login and the tokens the kernel's TokenAuthenticator resolves.
 *
 * Empty skeleton registered in the app so that its owner only edits this directory (docs/MODULES.md).
 */
object IdentityModule : ServerModule {
    override val name: String = "identity"

    override val koinModule: Module = module {}
}
