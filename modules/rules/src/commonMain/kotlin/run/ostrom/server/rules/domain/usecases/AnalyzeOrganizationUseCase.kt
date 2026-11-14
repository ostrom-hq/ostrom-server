// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules.domain.usecases

import run.ostrom.core.receipts.Digests
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.rules.domain.models.AnalysisRun
import run.ostrom.server.rules.domain.models.GatedRule
import run.ostrom.server.rules.domain.models.InvalidRuleException
import run.ostrom.server.rules.domain.models.LearnedWorkflow
import run.ostrom.server.rules.domain.models.ProcessProposal
import run.ostrom.server.rules.domain.models.RepositoryReadException
import run.ostrom.server.rules.domain.models.RepositoryRef
import run.ostrom.server.rules.domain.models.RepositorySnapshot
import run.ostrom.server.rules.domain.models.RepositoryTree
import run.ostrom.server.rules.domain.models.Rule
import run.ostrom.server.rules.domain.models.RuleProposal
import run.ostrom.server.rules.domain.models.RuleStatus
import run.ostrom.server.rules.domain.repositories.AnalysisRepository
import run.ostrom.server.rules.domain.repositories.RulesRepository
import run.ostrom.server.rules.domain.services.BuildFacts
import run.ostrom.server.rules.domain.services.ProcessInference
import run.ostrom.server.rules.domain.services.RepositoryContext
import run.ostrom.server.rules.domain.services.RepositoryFacts
import run.ostrom.server.rules.domain.services.RepositoryReader
import run.ostrom.server.rules.domain.services.RuleChecks
import run.ostrom.server.rules.domain.services.RuleInference
import run.ostrom.server.rules.domain.services.WorkflowProposals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Runs one analysis of an organization: reads its repositories, proposes the rules their facts state, then the
 * processes their files state as workflows, and reports each phase on the run so the console follows it.
 * Deterministic: no model, the same repositories give the same rules and workflows.
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
 * run proposes only what is not stored yet and never rewrites a rule, even one still proposed: a person may have
 * edited it. The team accepts a proposal like any rule, which signs the decision. The processes follow the same
 * rules as `proposed` workflows (`wf_learned_…`), in a phase after the rules, so a process's gate names a rule of the
 * same run (decision "one workflow system", section 4).
 *
 * @param analyses The runs.
 * @param rules The rules.
 * @param reader Reads the repositories, read-only.
 * @param workflows Where the process workflows are proposed.
 * @param logger Where a repository that cannot be read and an unexpected failure are logged.
 * @param clock The time.
 */
class AnalyzeOrganizationUseCaseImpl(
    analyses: AnalysisRepository,
    private val rules: RulesRepository,
    private val reader: RepositoryReader,
    private val workflows: WorkflowProposals,
    private val logger: Logger,
    private val clock: Clock,
) : AnalyzeOrganizationUseCase {
    private val runs = RunRecorder(analyses, clock, logger)

    override suspend fun invoke(run: AnalysisRun, repos: List<RepositoryRef>) {
        runs.execute(run, "analysis", ANALYSIS_DEADLINE) { analyse(run, repos) }
    }

    private suspend fun analyse(run: AnalysisRun, repos: List<RepositoryRef>) {
        val selected = repos.filterNot { it.archived }
            .sortedWith(compareByDescending<RepositoryRef> { it.pushedAt.orEmpty() }.thenBy { it.name })
            .take(MAX_REPOS)
        if (selected.isEmpty()) return runs.fail(run, GITHUB, ARCHIVED)
        // Each repository is read, inferred and let go before the next: 30 trees of 100,000 paths held at once would
        // pass the pod's memory.
        val proposals = mutableListOf<RuleProposal>()
        val processes = mutableListOf<ProcessProposal>()
        val items = mutableListOf<JsonObject>()
        selected.forEachIndexed { index, repo ->
            runs.advance(run, READ, READ_SHARE * index / selected.size)
            read(run, repo)?.let { snapshot ->
                val found = RuleInference.infer(snapshot)
                proposals += found
                val gated = found.mapNotNull { rule -> rule.gate?.let { GatedRule(learnedId(rule.key), it) } }
                val learned = ProcessInference.infer(snapshot, gated)
                processes += learned
                items += item(snapshot, learned.size)
            }
        }
        if (items.isEmpty()) return runs.fail(run, GITHUB, UNREAD)
        runs.advance(run, PROPOSE, READ_SHARE)
        val ruleIds = propose(run, proposals)
        runs.advance(run, PROCESSES, PROCESSES_SHARE)
        proposeProcesses(run, processes, ruleIds)
        runs.advance(run, AnalysisRun.READY, 1.0, items)
    }

    // One repository that cannot be read (a permission, a timeout) is left out; the others are still analysed.
    private suspend fun read(run: AnalysisRun, repo: RepositoryRef): RepositorySnapshot? = try {
        snapshot(run.orgId, repo)
    } catch (error: RepositoryReadException) {
        logger.warn("analysis could not read a repository", mapOf("run_id" to run.id, "repo" to repo.name), error)
        null
    }

    private suspend fun snapshot(orgId: Uuid, repo: RepositoryRef): RepositorySnapshot {
        val listed = reader.tree(orgId, repo)
        // Past MAX_PATHS, the list is cut and said cut, as GitHub cuts its own.
        val tree = if (listed.paths.size > MAX_PATHS) RepositoryTree(listed.paths.take(MAX_PATHS), true) else listed
        val files = (RepositoryFacts.wantedFiles(tree.paths) + ProcessInference.wantedFiles(tree.paths)).distinct()
            .mapNotNull { path -> reader.file(orgId, repo, path)?.let { path to it } }
            .toMap()
        return RepositorySnapshot(repo, tree, files, reader.protection(orgId, repo))
    }

    // A proposal is written once: a rule that exists (proposed, edited, decided or written by the team) is the
    // team's from then on, and a new run never rewrites it.
    private suspend fun propose(run: AnalysisRun, proposals: List<RuleProposal>): Set<String> {
        val now = clock.now()
        val stored = proposals.distinctBy { it.key }.take(MAX_PROPOSALS).mapNotNull { checked(run, it, now) }
        rules.transaction(run.orgId) {
            for (rule in stored) {
                if (lockRule(rule.id) == null) insertRule(rule)
            }
        }
        return stored.map { it.id }.toSet()
    }

    // The same, for the processes: a gate names only a rule this run kept, and a workflow is written once.
    private suspend fun proposeProcesses(run: AnalysisRun, processes: List<ProcessProposal>, ruleIds: Set<String>) {
        val learned = processes.distinctBy { it.key }.take(ProcessInference.MAX_PROPOSALS).map { process ->
            val nodes = process.nodes.filter { node -> node.rule?.let { it.id in ruleIds } ?: true }
            LearnedWorkflow(WORKFLOW_PREFIX + digest(process.key), process.copy(nodes = nodes))
        }
        workflows.propose(run.orgId, learned, clock.now())
    }

    // The same bounds as a rule the console writes; a finding past them (a path of 300 characters) is left out.
    private fun checked(run: AnalysisRun, proposal: RuleProposal, now: Instant): Rule? = try {
        Rule(
            orgId = run.orgId,
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
    } catch (error: InvalidRuleException) {
        logger.warn("analysis left out a proposal", mapOf("run_id" to run.id, "key" to proposal.key), error)
        null
    }

    // As the contract's `Repo`: what the console lists under the analysis.
    private fun item(snapshot: RepositorySnapshot, processes: Int): JsonObject = buildJsonObject {
        val repo = snapshot.repo
        put("name", repo.name)
        repo.description?.let { put("description", it) }
        BuildFacts.languages(snapshot.tree.paths).firstOrNull()?.let { put("language", it.name) }
        put("defaultBranch", repo.defaultBranch)
        put("archived", repo.archived)
        repo.pushedAt?.let { put("pushedAt", it) }
        putJsonArray("owners") { RepositoryContext(snapshot).defaultOwners.forEach { add(it.removePrefix("@")) } }
        put("processes", processes)
    }

    private fun learnedId(key: String): String = PREFIX + digest(key)

    private fun digest(key: String): String = Digests.sha256Hex(key.encodeToByteArray()).take(ID_HEX)

    /** The bounds and words of an analysis. */
    companion object {
        /** The repositories one run reads at most: the most recently pushed. */
        const val MAX_REPOS: Int = 30

        /** The proposals one run stores at most. */
        const val MAX_PROPOSALS: Int = 400

        /** The paths of one repository the analysis reads at most. */
        const val MAX_PATHS: Int = 20_000

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

        /** The phase while process workflows are proposed. */
        const val PROCESSES: String = "processes"

        /** The id prefix of a learned workflow. */
        const val WORKFLOW_PREFIX: String = "wf_learned_"

        private const val ID_HEX = 24
        private const val READ_SHARE = 0.9
        private const val PROCESSES_SHARE = 0.95
        private const val GITHUB = "github"
        private const val ARCHIVED = "Every repository the GitHub App sees is archived: there is nothing to analyse."
        private const val UNREAD = "Ostrom could not read any repository of the organization on GitHub; see the " +
            "server log, then start the analysis again."
    }
}

// How long an analysis may take: 30 repositories, six GitHub calls each, take seconds when GitHub answers. Top-level:
// a companion property has a getter nothing calls, which Kover counts.
private val ANALYSIS_DEADLINE = 15.minutes
