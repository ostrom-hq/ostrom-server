// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules.fakes

import run.ostrom.core.api.models.WorkflowStatus
import run.ostrom.server.compiler.domain.models.StoredWorkflow
import run.ostrom.server.compiler.domain.usecases.WorkflowsUseCase
import run.ostrom.server.rules.domain.models.AnalysisKind
import run.ostrom.server.rules.domain.models.AnalysisRun
import run.ostrom.server.rules.domain.repositories.AnalysisRepository
import run.ostrom.server.rules.domain.repositories.AnalysisTransaction
import run.ostrom.server.rules.domain.services.AnalysisEngine
import run.ostrom.server.rules.domain.services.AnalysisStart
import kotlinx.serialization.json.JsonObject
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

    override fun check(raw: JsonObject): Unit = error("not checked")
}
