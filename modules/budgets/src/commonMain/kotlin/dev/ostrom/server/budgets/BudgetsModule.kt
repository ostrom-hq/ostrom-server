package dev.ostrom.server.budgets

import dev.ostrom.server.kernel.module.ServerModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The budgets module: budgets, quotas and their enforcement on the gateway.
 *
 * Empty skeleton registered in the app so that its owner only edits this directory (docs/MODULES.md).
 */
object BudgetsModule : ServerModule {
    override val name: String = "budgets"

    override val koinModule: Module = module {}
}
