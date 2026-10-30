package dev.ostrom.server.secrets

import dev.ostrom.server.kernel.module.ServerModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The secrets module: encrypted secrets and their injection into runs.
 *
 * Empty skeleton registered in the app so that its owner only edits this directory (docs/MODULES.md).
 */
object SecretsModule : ServerModule {
    override val name: String = "secrets"

    override val koinModule: Module = module {}
}
