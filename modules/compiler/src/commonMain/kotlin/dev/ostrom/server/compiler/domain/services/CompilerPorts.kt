// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler.domain.services

import dev.ostrom.server.compiler.domain.models.CompilerSettings
import dev.ostrom.server.compiler.domain.models.DecisionDoc
import dev.ostrom.server.compiler.domain.models.RuleDoc
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
