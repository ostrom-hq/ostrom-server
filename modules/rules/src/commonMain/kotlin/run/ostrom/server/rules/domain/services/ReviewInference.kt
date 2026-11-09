// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.rules.domain.services

import run.ostrom.core.receipts.Digests
import run.ostrom.server.rules.domain.models.BranchProtection
import run.ostrom.server.rules.domain.models.RuleGate
import run.ostrom.server.rules.domain.models.RuleGateAction
import run.ostrom.server.rules.domain.models.RuleProposal
import run.ostrom.server.rules.domain.models.RuleStrength
import run.ostrom.server.rules.domain.services.RepositoryContext.Companion.counted
import run.ostrom.server.rules.domain.services.RepositoryContext.Companion.quoted
import run.ostrom.server.rules.domain.services.RepositoryContext.Companion.shown

/**
 * The review rules a repository states on GitHub: who CODEOWNERS names for each path, and how its default branch is
 * protected. A CODEOWNERS directory whose review the branch requires becomes a gated directory: an agent's change to
 * it waits for a yes from its owners.
 */
object ReviewInference {
    private const val MAX_OWNER_RULES = 20
    private const val STATED = 0.95
    private const val OWNED_GATE = 0.9
    private const val OWNED = 0.85
    private const val UNPROTECTED = 0.6
    private const val UNREADABLE = 0.5
    private const val REVIEWS = "Reviews"
    private const val BRANCHES = "Branches"
    private const val SLUG = 48
    private const val DIGEST = 8
    private const val PUSH_MAIN = "push-main"
    private const val PUSH_LABEL = "Push to the main branch"
    private val mainBranches = setOf("main", "master")
    private val nonWord = Regex("[^a-z0-9]+")

    /**
     * The rules of the CODEOWNERS entries that name owners.
     *
     * @param context The repository.
     * @return One rule per entry, at most 20; a gate when the branch requires the code owners' review.
     */
    fun owners(context: RepositoryContext): List<RuleProposal> {
        val path = context.codeownersPath ?: return emptyList()
        val required = context.snapshot.protection?.codeOwnerReviews == true
        // GitHub applies the last entry of a pattern: the last ones are kept.
        val entries = context.owners.filter { it.owners.isNotEmpty() }
            .asReversed()
            .distinctBy { it.pattern }
            .asReversed()
        return entries.takeLast(MAX_OWNER_RULES).map {
            val named = it.owners.joinToString(", ", transform = ::shown)
            val pattern = shown(it.pattern)
            val base = RuleInference.proposal(context, "owners:${it.pattern}", "", "rule").copy(
                group = REVIEWS,
                sources = listOf(context.file(path)),
                strength = if (required) RuleStrength.REQUIRED else RuleStrength.RECOMMENDED,
                confidence = OWNED,
            )
            when {
                it.pattern in RepositoryContext.catchAll -> base.copy(
                    statement = "Every change to ${context.name} is reviewed by $named.",
                    why = "CODEOWNERS names them for every file.",
                    confidence = OWNED_GATE,
                )
                // A pattern a hook could not match in a bounded time is stated, not gated (CodeOwners.gateable).
                required && CodeOwners.gateable(it.pattern) -> base.copy(
                    statement = "A change to `$pattern` in ${context.name} needs a yes from its code owners " +
                        "($named).",
                    output = "gate",
                    why = "CODEOWNERS names them and the protection of `${context.shownBranch}` requires their review.",
                    confidence = OWNED_GATE,
                    gate = ownedGate(context, it.pattern, CodeOwners.logins(it.owners), path),
                )
                else -> base.copy(
                    statement = "In ${context.name}, `$pattern` is reviewed by $named.",
                    why = "CODEOWNERS names them: GitHub asks for their review on each pull request that touches it.",
                )
            }
        }
    }

    private fun ownedGate(context: RepositoryContext, pattern: String, approvers: List<String>, path: String) =
        RuleGate(
            actions = listOf(
                RuleGateAction(
                    cls = ownedClass(pattern),
                    label = "Change `${shown(pattern)}`",
                    patterns = listOf(CodeOwners.gatePattern(pattern)),
                    repos = listOf(context.name),
                    tools = RepositoryContext.editTools,
                ),
            ),
            approvers = approvers,
            reason = "code owners in $path",
        )

    // One class per owned pattern: the gates catalogue keeps one set of patterns per class and repository, so the
    // pattern's digest keeps `*.sql` and `/sql/` (or `/Docs/` and `/docs/`) apart.
    private fun ownedClass(pattern: String): String {
        val slug = pattern.lowercase().replace(nonWord, "-").trim('-').take(SLUG).trimEnd('-').ifEmpty { "root" }
        return "edit-owned-$slug-" + Digests.sha256Hex(pattern.encodeToByteArray()).take(DIGEST)
    }

    /**
     * The rule of the default branch: never push to it when GitHub protects it, a yes before a push when it does not
     * or when its protection could not be read.
     *
     * @param context The repository.
     * @return The rule.
     */
    fun branch(context: RepositoryContext): RuleProposal {
        val protection = context.snapshot.protection
        val branch = context.shownBranch
        val statement = "A push to `$branch` of ${context.name} needs a yes."
        val base = RuleInference.proposal(context, "branch", statement, "gate")
            .copy(group = BRANCHES, sources = listOf(context.settings()), gate = pushGate(context))
        return when {
            protection == null -> base.copy(
                why = "Ostrom could not read how GitHub protects `$branch`: a yes before a push to it is the safe " +
                    "default.",
                confidence = UNREADABLE,
            )
            !protection.protected -> base.copy(
                why = "GitHub does not protect `$branch`: anyone with write access, an agent included, can push to it.",
                confidence = UNPROTECTED,
            )
            else -> base.copy(
                statement = protectedStatement(context, protection),
                why = protectedWhy(branch, protection),
                strength = RuleStrength.REQUIRED,
                confidence = STATED,
            )
        }
    }

    private fun protectedStatement(context: RepositoryContext, protection: BranchProtection): String {
        val conditions = listOfNotNull(
            counted(protection.requiredApprovals, "approval").takeIf { protection.requiredApprovals > 0 },
            "the checks ${quoted(protection.requiredChecks)} pass".takeIf { protection.requiredChecks.isNotEmpty() },
        )
        val after = if (conditions.isEmpty()) "" else ", which merges after " + conditions.joinToString(" and ")
        return "Never push to `${context.shownBranch}` of ${context.name}: open a pull request$after."
    }

    private fun protectedWhy(branch: String, protection: BranchProtection): String {
        val facts = listOfNotNull(
            if (protection.requiredApprovals > 0) {
                counted(protection.requiredApprovals, "approval") + " required"
            } else {
                "no approval required"
            },
            "code owners must approve".takeIf { protection.codeOwnerReviews },
            counted(protection.requiredChecks.size, "required check").takeIf { protection.requiredChecks.isNotEmpty() },
            "force-push refused".takeIf { protection.blocksForcePush },
        )
        return "GitHub protects `$branch`: ${facts.joinToString(", ")}."
    }

    // The built-in class covers main and master; another default branch gets its own pattern.
    private fun pushGate(context: RepositoryContext): RuleGate {
        val patterns = if (context.branch in mainBranches) {
            emptyList()
        } else {
            listOf("\\bgit\\s+push\\b[^\\n;&|]*\\b${CodeOwners.literal(context.branch)}\\b")
        }
        val approvers = context.approvers(context.defaultOwners)
        return RuleGate(
            actions = listOf(RuleGateAction(PUSH_MAIN, PUSH_LABEL, patterns, listOf(context.name), null)),
            approvers = approvers,
            reason = context.codeownersPath?.takeIf { approvers.isNotEmpty() }?.let { "default code owners in $it" },
        )
    }
}
