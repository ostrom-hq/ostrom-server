// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.artifacts

import dev.ostrom.server.kernel.module.ServerModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The artifacts module: published artifacts (pages and files) and their sharing.
 *
 * Empty skeleton registered in the app so that its owner only edits this directory (docs/MODULES.md).
 */
object ArtifactsModule : ServerModule {
    override val name: String = "artifacts"

    override val koinModule: Module = module {}
}
