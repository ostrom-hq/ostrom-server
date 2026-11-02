// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.domain.usecases

import dev.ostrom.core.receipts.Digests
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.rules.domain.models.AnalysisFailure
import dev.ostrom.server.rules.domain.models.AnalysisRun
import dev.ostrom.server.rules.domain.models.InvalidRuleException
import dev.ostrom.server.rules.domain.models.RepositoryReadException
import dev.ostrom.server.rules.domain.models.RepositoryRef
import dev.ostrom.server.rules.domain.models.RepositorySnapshot
import dev.ostrom.server.rules.domain.models.Rule
import dev.ostrom.server.rules.domain.models.RuleProposal
import dev.ostrom.server.rules.domain.models.RuleStatus
import dev.ostrom.server.rules.domain.repositories.AnalysisRepository
import dev.ostrom.server.rules.domain.repositories.RulesRepository
import dev.ostrom.server.rules.domain.services.BuildFacts
import dev.ostrom.server.rules.domain.services.RepositoryContext
import dev.ostrom.server.rules.domain.services.RepositoryFacts
import dev.ostrom.server.rules.domain.services.RepositoryReader
import dev.ostrom.server.rules.domain.services.RuleChecks
import dev.ostrom.server.rules.domain.services.RuleInference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Runs one analysis of an organization: reads its repositories, proposes the rules their facts state, and reports
 * each phase on the run so the console follows it. Deterministic: no model, the same repositories give the same rules.
 */
interface AnalyzeOrganizationUseCase {
    /**
     * Runs it to its end: `ready`, or `failed` with a stage and a reason. A run a newer one replaced stops without
     * writing anything more.
     *
     * @param run The run, already recorded.
     * @param repos The repositories its organization's installation lists.
     */
    suspend operator fun invoke(run: AnalysisRun, repos: List<RepositoryRef>)
}

/**
 * [AnalyzeOrganizationUseCase] on the runs, the rules and the repository reader.
 *
 * Each proposal is stored as a `proposed`, `learned` rule under an id derived from what it was found from: a later
 * run refreshes a proposal still undecided, and never touches a rule the team decided, rejected or wrote. The team
 * accepts a proposal like any rule, which signs the decision.
 *
 * @param analyses The runs.
 * @param rules The rules.
 * @param reader Reads the repositories, read-only.
 * @param logger Where a repository that cannot be read and an unexpected failure are logged.
 * @param clock The time.
 */
class AnalyzeOrganizationUseCaseImpl(
    private val analyses: AnalysisRepository,
    private val rules: RulesRepository,
    private val reader: RepositoryReader,
    private val logger: Logger,
    private val clock: Clock,
) : AnalyzeOrganizationUseCase {
    override suspend fun invoke(run: AnalysisRun, repos: List<RepositoryRef>) {
        try {
            analyse(run, repos)
        } catch (_: SupersededRunException) {
            // A newer run took the organization's analysis over (this one stalled past the hour): it owns the state.
            logger.debug("analysis run superseded", mapOf("run_id" to run.id))
        } catch (error: CancellationException) {
            // The server stops: the run says so rather than staying "running" until it goes stale.
            withContext(NonCancellable) { fail(run, ENGINE, STOPPED) }
            throw error
        } catch (error: Exception) {
            logger.error("analysis failed", mapOf("run_id" to run.id), error)
            fail(run, ENGINE, BROKE)
        }
    }

    private suspend fun analyse(run: AnalysisRun, repos: List<RepositoryRef>) {
        val selected = repos.filterNot { it.archived }
            .sortedWith(compareByDescending<RepositoryRef> { it.pushedAt.orEmpty() }.thenBy { it.name })
            .take(MAX_REPOS)
        if (selected.isEmpty()) return fail(run, GITHUB, ARCHIVED)
        val snapshots = mutableListOf<RepositorySnapshot>()
        selected.forEachIndexed { index, repo ->
            advance(run, READ, READ_SHARE * index / selected.size)
            read(run, repo)?.let { snapshots += it }
        }
        if (snapshots.isEmpty()) return fail(run, GITHUB, UNREAD)
        advance(run, PROPOSE, READ_SHARE)
        propose(run.orgId, snapshots.flatMap(RuleInference::infer))
        advance(run, AnalysisRun.READY, 1.0, snapshots.map(::item))
    }

    // One repository that cannot be read (a permission, a timeout) is left out; the others are still analysed.
    private suspend fun read(run: AnalysisRun, repo: RepositoryRef): RepositorySnapshot? = try {
        snapshot(run.orgId, repo)
    } catch (error: RepositoryReadException) {
        logger.warn("analysis could not read a repository", mapOf("run_id" to run.id, "repo" to repo.name), error)
        null
    }

    private suspend fun snapshot(orgId: Uuid, repo: RepositoryRef): RepositorySnapshot {
        val tree = reader.tree(orgId, repo)
        val files = RepositoryFacts.wantedFiles(tree.paths)
            .mapNotNull { path -> reader.file(orgId, repo, path)?.let { path to it } }
            .toMap()
        return RepositorySnapshot(repo, tree, files, reader.protection(orgId, repo))
    }

    private suspend fun propose(orgId: Uuid, proposals: List<RuleProposal>) {
        val now = clock.now()
        val stored = proposals.distinctBy { it.key }.take(MAX_PROPOSALS).mapNotNull { checked(orgId, it, now) }
        rules.transaction(orgId) {
            for (rule in stored) {
                val current = lockRule(rule.id)
                when {
                    current == null -> insertRule(rule)
                    // Still undecided and still ours: refreshed. Decided, rejected or written: the team's.
                    current.status == RuleStatus.PROPOSED && current.origin == LEARNED ->
                        updateRule(rule.copy(foundAt = current.foundAt))
                }
            }
        }
    }

    // The same bounds as a rule the console writes; a finding past them (a path of 300 characters) is left out.
    private fun checked(orgId: Uuid, proposal: RuleProposal, now: Instant): Rule? = try {
        Rule(
            orgId = orgId,
            id = learnedId(proposal.key),
            statement = RuleChecks.statement(proposal.statement),
            output = RuleChecks.output(proposal.output),
            group = RuleChecks.group(proposal.group),
            scope = RuleChecks.scope(proposal.scope),
            sources = proposal.sources,
            why = RuleChecks.why(proposal.why),
            strength = proposal.strength,
            confidence = proposal.confidence,
            status = RuleStatus.PROPOSED,
            foundBy = FOUND_BY,
            foundAt = now,
            reviewBy = null,
            decidedBy = null,
            decidedAt = null,
            origin = LEARNED,
            gate = proposal.gate?.let(RuleChecks::gate),
            updatedAt = now,
        )
    } catch (_: InvalidRuleException) {
        null
    }

    // As the contract's `Repo`: what the console lists under the analysis.
    private fun item(snapshot: RepositorySnapshot): JsonObject = buildJsonObject {
        val repo = snapshot.repo
        put("name", repo.name)
        repo.description?.let { put("description", it) }
        BuildFacts.languages(snapshot.tree.paths).firstOrNull()?.let { put("language", it.name) }
        put("defaultBranch", repo.defaultBranch)
        put("archived", repo.archived)
        repo.pushedAt?.let { put("pushedAt", it) }
        putJsonArray("owners") { RepositoryContext(snapshot).defaultOwners.forEach { add(it.removePrefix("@")) } }
    }

    private suspend fun advance(run: AnalysisRun, phase: String, progress: Double, items: List<JsonObject>? = null) {
        val at = clock.now()
        val ours = analyses.transaction(run.orgId) {
            val current = lock(run.kind)
            if (current == null || current.id != run.id) return@transaction false
            put(current.copy(phase = phase, progress = progress, updatedAt = at, items = items ?: current.items))
            true
        }
        if (!ours) throw SupersededRunException()
    }

    // The last results stay: a failed run erases nothing.
    private suspend fun fail(run: AnalysisRun, stage: String, reason: String) {
        val at = clock.now()
        analyses.transaction(run.orgId) {
            val current = lock(run.kind)
            if (current != null && current.id == run.id) {
                put(
                    current.copy(
                        phase = AnalysisRun.FAILED,
                        failure = AnalysisFailure(stage, reason, at, run.kind.retryPath),
                        updatedAt = at,
                    ),
                )
            }
        }
    }

    private fun learnedId(key: String): String = PREFIX + Digests.sha256Hex(key.encodeToByteArray()).take(ID_HEX)

    /** The bounds and words of an analysis. */
    companion object {
        /** The repositories one run reads at most: the most recently pushed. */
        const val MAX_REPOS: Int = 30

        /** The proposals one run stores at most. */
        const val MAX_PROPOSALS: Int = 400

        /** Who finds a learned rule. */
        const val FOUND_BY: String = "analysis"

        /** A rule the analysis found. */
        const val LEARNED: String = "learned"

        /** The id prefix of a learned rule. */
        const val PREFIX: String = "rule_learned_"

        /** The phase while repositories are read. */
        const val READ: String = "read"

        /** The phase while rules are proposed. */
        const val PROPOSE: String = "propose"

        private const val ID_HEX = 24
        private const val READ_SHARE = 0.9
        private const val ENGINE = "engine"
        private const val GITHUB = "github"
        private const val ARCHIVED = "Every repository the GitHub App sees is archived: there is nothing to analyse."
        private const val UNREAD = "Ostrom could not read any repository of the organization on GitHub; see the " +
            "server log, then start the analysis again."
        private const val STOPPED = "The server stopped during the analysis; start it again."
        private const val BROKE = "The analysis stopped on an unexpected error; see the server log."
    }
}

// A newer run replaced this one: it stops without writing.
private class SupersededRunException : Exception("The analysis run was superseded.")
