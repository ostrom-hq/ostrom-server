// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.domain.services

import run.ostrom.core.api.models.Workflow
import run.ostrom.server.compiler.domain.models.CompilerSettings
import run.ostrom.server.compiler.domain.models.DecisionDoc
import run.ostrom.server.compiler.domain.models.RuleDoc
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What the compiler reads from the other domains: the decided rules and their decisions, the organization's
 * repositories and its settings. The module that owns rules binds it; until then nothing is ruled.
 */
interface CompilerSources {
    /**
     * Lists the rules.
     *
     * @param orgId The organization.
     * @return Its rules, as saved.
     */
    suspend fun rules(orgId: Uuid): List<RuleDoc>

    /**
     * Lists the decisions taken on rules.
     *
     * @param orgId The organization.
     * @return The decisions.
     */
    suspend fun ruleDecisions(orgId: Uuid): List<DecisionDoc>

    /**
     * Lists the repository names.
     *
     * @param orgId The organization.
     * @return The names.
     */
    suspend fun repos(orgId: Uuid): List<String>

    /**
     * Reads how the organization compiles.
     *
     * @param orgId The organization.
     * @return Its settings.
     */
    suspend fun settings(orgId: Uuid): CompilerSettings

    /**
     * The gates the organization's rules put on boundary actions, as one-gate workflows that the gates catalogue reads
     * after the saved ones (a saved workflow's gate wins a tie).
     *
     * @param orgId The organization.
     * @return The workflows; none until the module that owns rules binds its sources.
     */
    suspend fun ruleGates(orgId: Uuid): List<Workflow> = emptyList()
}

/**
 * Signs the receipt of a rules harness: subject kind `harness`, initiator `ostrom compiler`, agent `ostrom export`.
 */
interface HarnessReceiptSigner {
    /**
     * Signs it once per version.
     *
     * @param orgId The organization.
     * @param version The harness version.
     * @param digest The harness digest.
     * @param rules The rules it carries.
     * @param at When.
     * @return The receipt id.
     */
    suspend fun sign(orgId: Uuid, version: String, digest: String, rules: List<HarnessRule>, at: Instant): String
}
