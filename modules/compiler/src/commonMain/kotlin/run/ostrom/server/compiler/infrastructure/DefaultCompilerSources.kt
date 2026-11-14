// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.infrastructure

import run.ostrom.server.compiler.domain.models.CompilerSettings
import run.ostrom.server.compiler.domain.models.DecisionDoc
import run.ostrom.server.compiler.domain.models.RuleDoc
import run.ostrom.server.compiler.domain.repositories.CompilerSettingsRepository
import run.ostrom.server.compiler.domain.services.CompilerSources
import kotlin.uuid.Uuid

/**
 * The compiler's sources while no module owns rules or repositories: no rule, no repository, and the settings each
 * organization's owners and admins save in the console (`GET /api/v1/packages/settings`), the defaults until they do.
 *
 * @param settings The stored settings, read at each compilation.
 */
class DefaultCompilerSources(private val settings: CompilerSettingsRepository) : CompilerSources {
    override suspend fun rules(orgId: Uuid): List<RuleDoc> = emptyList()

    override suspend fun ruleDecisions(orgId: Uuid): List<DecisionDoc> = emptyList()

    override suspend fun repos(orgId: Uuid): List<String> = emptyList()

    override suspend fun settings(orgId: Uuid): CompilerSettings = settings.read(orgId)?.settings ?: CompilerSettings()
}
