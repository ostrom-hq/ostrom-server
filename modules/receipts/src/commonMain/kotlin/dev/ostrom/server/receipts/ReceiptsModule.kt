package dev.ostrom.server.receipts

import dev.ostrom.server.kernel.module.ServerModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The receipts module: signed receipts of what an agent did, byte-compatible with the prototype.
 *
 * Empty skeleton registered in the app so that its owner only edits this directory (docs/MODULES.md).
 */
object ReceiptsModule : ServerModule {
    override val name: String = "receipts"

    override val koinModule: Module = module {}
}
