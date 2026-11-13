// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.infrastructure

import run.ostrom.server.compiler.domain.models.CompilerSettings
import run.ostrom.server.compiler.domain.models.DecisionDoc
import run.ostrom.server.compiler.domain.models.RuleDoc
import run.ostrom.server.compiler.domain.services.CompilerSources
import run.ostrom.server.kernel.config.ConfigSource
import kotlin.uuid.Uuid

/**
 * The compiler's sources while no module owns rules, repositories or settings: no rule, no repository, and the
 * prototype's environment settings (`OSTROM_PIN=manual`, `OSTROM_CODE_PUBLISHERS=a,b`, `OSTROM_PROD_APPROVER`).
 *
 * @param config Where the settings are read.
 */
class DefaultCompilerSources(config: ConfigSource) : CompilerSources {
    private val settings = CompilerSettings(
        manualPin = config["OSTROM_PIN"] == "manual",
        codePublishers = config["OSTROM_CODE_PUBLISHERS"]?.let { raw ->
            raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        },
        prodApprover = config["OSTROM_PROD_APPROVER"],
    )

    override suspend fun rules(orgId: Uuid): List<RuleDoc> = emptyList()

    override suspend fun ruleDecisions(orgId: Uuid): List<DecisionDoc> = emptyList()

    override suspend fun repos(orgId: Uuid): List<String> = emptyList()

    override suspend fun settings(orgId: Uuid): CompilerSettings = settings
}
