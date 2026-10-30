// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.domain.models

/**
 * A rule the analysis proposes, before it is stored. The team accepts, edits or rejects it like any rule
 * (`PATCH /rules/{id}`, `/rules/bulk`); a proposal is never a decision and signs nothing.
 *
 * @property key What it was found from (`area:api:ci`): the same finding in a later run gives the same key, so the
 *   same rule is refreshed rather than proposed twice.
 * @property statement What it says.
 * @property output `rule`, `gate`, `check` or `note`.
 * @property group The display group.
 * @property scope The repository, or paths under it (`api`, `api/db/migration/`).
 * @property sources The files or settings it was read from.
 * @property why What was counted, in one or two sentences.
 * @property strength Must or should.
 * @property confidence How sure the analysis is, from 0 to 1: high for a fact GitHub states (a CODEOWNERS line, a
 *   branch protection), lower for a safe default the repository does not state.
 * @property gate The gate it puts on boundary actions, if any.
 */
data class RuleProposal(
    val key: String,
    val statement: String,
    val output: String,
    val group: String,
    val scope: List<String>,
    val sources: List<RuleSource>,
    val why: String,
    val strength: RuleStrength,
    val confidence: Double,
    val gate: RuleGate?,
)
