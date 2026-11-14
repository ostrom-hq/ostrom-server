// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.infrastructure

import run.ostrom.server.compiler.domain.models.CompilerSettings
import run.ostrom.server.compiler.domain.models.DecisionDoc
import run.ostrom.server.compiler.domain.models.RuleDoc
import run.ostrom.server.compiler.domain.repositories.CompilerSettingsRepository
import run.ostrom.server.compiler.domain.services.CompilerSources
import run.ostrom.server.kernel.config.ConfigSource
import kotlin.uuid.Uuid

/**
 * The compiler's sources while no module owns rules or repositories: no rule, no repository, and the settings each
 * organization's owners and admins save in the console (`GET /api/v1/packages/settings`). Until an organization saves
 * its own, the former instance-wide variables apply, deprecated, so that an upgrade never loosens a policy an
 * operator set (`OSTROM_PIN=manual`, `OSTROM_CODE_PUBLISHERS=a,b`, `OSTROM_PROD_APPROVER`); else the defaults.
 *
 * @param settings The stored settings, read at each compilation.
 * @param legacy The former variables' settings, for an organization that saved none.
 */
class DefaultCompilerSources(
    private val settings: CompilerSettingsRepository,
    private val legacy: CompilerSettings = CompilerSettings(),
) : CompilerSources {
    override suspend fun rules(orgId: Uuid): List<RuleDoc> = emptyList()

    override suspend fun ruleDecisions(orgId: Uuid): List<DecisionDoc> = emptyList()

    override suspend fun repos(orgId: Uuid): List<String> = emptyList()

    override suspend fun settings(orgId: Uuid): CompilerSettings = settings.read(orgId)?.settings ?: legacy

    /** The former instance-wide compile policy, read once at start. */
    companion object {
        /** The variables, in the order they are named. */
        val VARIABLES: List<String> = listOf("OSTROM_PIN", "OSTROM_CODE_PUBLISHERS", "OSTROM_PROD_APPROVER")

        /**
         * The settings the former variables set.
         *
         * @param config The server's configuration.
         * @return The settings, the defaults where a variable is unset.
         */
        fun legacy(config: ConfigSource): CompilerSettings = CompilerSettings(
            manualPin = config["OSTROM_PIN"] == "manual",
            codePublishers = config["OSTROM_CODE_PUBLISHERS"]?.let { raw ->
                raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            },
            prodApprover = config["OSTROM_PROD_APPROVER"],
        )
    }
}
