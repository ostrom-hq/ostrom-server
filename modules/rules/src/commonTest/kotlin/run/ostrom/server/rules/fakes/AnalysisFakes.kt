// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules.fakes

import run.ostrom.core.api.models.WorkflowStatus
import run.ostrom.server.compiler.domain.models.Authorship
import run.ostrom.server.compiler.domain.models.DecisionDoc
import run.ostrom.server.compiler.domain.models.StoredWorkflow
import run.ostrom.server.compiler.domain.repositories.WorkflowsRepository
import run.ostrom.server.compiler.domain.services.CompilerDocs
import run.ostrom.server.compiler.domain.usecases.WorkflowsUseCase
import run.ostrom.server.rules.domain.models.AnalysisKind
import run.ostrom.server.rules.domain.models.AnalysisRun
import run.ostrom.server.rules.domain.models.LearnedWorkflow
import run.ostrom.server.rules.domain.repositories.AnalysisRepository
import run.ostrom.server.rules.domain.repositories.AnalysisTransaction
import run.ostrom.server.rules.domain.services.AnalysisEngine
import run.ostrom.server.rules.domain.services.AnalysisStart
import run.ostrom.server.rules.domain.services.WorkflowProposals
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The runs in memory, one per organization and kind. */
class FakeAnalysisRepository : AnalysisRepository {
    val runs = mutableMapOf<Pair<Uuid, AnalysisKind>, AnalysisRun>()
    var transactions = 0

    override suspend fun run(orgId: Uuid, kind: AnalysisKind): AnalysisRun? = runs[orgId to kind]

    override suspend fun <T> transaction(orgId: Uuid, block: suspend AnalysisTransaction.() -> T): T {
        transactions++
        return object : AnalysisTransaction {
            override suspend fun lock(kind: AnalysisKind): AnalysisRun? = runs[orgId to kind]

            override suspend fun put(run: AnalysisRun) {
                runs[orgId to run.kind] = run
            }
        }.block()
    }
}

/** Answers [answer], or throws [failure]; records the runs it was asked. */
class FakeAnalysisEngine(
    var answer: AnalysisStart = AnalysisStart.Started,
    var failure: Throwable? = null,
) : AnalysisEngine {
    val started = mutableListOf<AnalysisRun>()

    override suspend fun start(run: AnalysisRun): AnalysisStart {
        started += run
        failure?.let { throw it }
        return answer
    }
}

/** The compiler's workflows, as given. */
class FakeWorkflows(private val workflows: List<StoredWorkflow> = emptyList()) : WorkflowsUseCase {
    override suspend fun list(orgId: Uuid, repo: String?, status: WorkflowStatus?): List<StoredWorkflow> = workflows

    override suspend fun get(orgId: Uuid, id: String): StoredWorkflow = error("not read")

    override suspend fun save(
        orgId: Uuid,
        raw: JsonObject,
        expectedVersion: Int?,
        by: String,
        create: Boolean,
    ): StoredWorkflow = error("not written")

    override suspend fun delete(orgId: Uuid, id: String) = error("not deleted")

    /** The documents checked, in order. */
    val checked = mutableListOf<JsonObject>()

    override fun check(raw: JsonObject) {
        checked += raw
    }
}

/** The compiler's workflow storage in memory: a creation takes a free id only. */
class FakeWorkflowsRepository : WorkflowsRepository {
    val workflows = mutableMapOf<Pair<Uuid, String>, StoredWorkflow>()

    /** Set to pretend the organization keeps this many workflows. */
    var count: Int? = null

    override suspend fun workflows(orgId: Uuid): List<StoredWorkflow> =
        workflows.filterKeys { it.first == orgId }.values.toList()

    override suspend fun workflow(orgId: Uuid, id: String): StoredWorkflow? = workflows[orgId to id]

    override suspend fun saveWorkflow(
        orgId: Uuid,
        id: String,
        raw: JsonObject,
        expectedVersion: Int?,
        author: Authorship,
    ): StoredWorkflow? {
        check(expectedVersion == null) { "a proposal only creates" }
        if ((orgId to id) in workflows) return null
        return StoredWorkflow(CompilerDocs.workflow(raw), 1, author.by, author.at).also { workflows[orgId to id] = it }
    }

    override suspend fun deleteWorkflow(orgId: Uuid, id: String): Boolean = error("not deleted")

    override suspend fun workflowCount(orgId: Uuid): Int = count ?: workflows.keys.count { it.first == orgId }

    override suspend fun lastDecisions(orgId: Uuid): List<DecisionDoc> = error("not read")

    override suspend fun addDecision(orgId: Uuid, decision: DecisionDoc) = error("a proposal is not a decision")
}

/** Records the process workflows an analysis proposes, and stores them all. */
class FakeWorkflowProposals : WorkflowProposals {
    val proposed = mutableListOf<Triple<Uuid, List<LearnedWorkflow>, Instant>>()

    override suspend fun propose(orgId: Uuid, workflows: List<LearnedWorkflow>, at: Instant): Int {
        proposed += Triple(orgId, workflows, at)
        return workflows.size
    }
}
