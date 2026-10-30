// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler

import dev.ostrom.server.compiler.domain.models.Authorship
import dev.ostrom.server.compiler.domain.models.CompilerSettings
import dev.ostrom.server.compiler.domain.models.DecisionDoc
import dev.ostrom.server.compiler.domain.models.PackageAudit
import dev.ostrom.server.compiler.domain.models.PackagePin
import dev.ostrom.server.compiler.domain.models.RuleDoc
import dev.ostrom.server.compiler.domain.models.StoredPackage
import dev.ostrom.server.compiler.domain.models.StoredWorkflow
import dev.ostrom.server.compiler.domain.repositories.CompilerRepository
import dev.ostrom.server.compiler.domain.services.CompilerDocs
import dev.ostrom.server.compiler.domain.services.CompilerSources
import dev.ostrom.server.compiler.domain.services.HarnessReceiptSigner
import dev.ostrom.server.compiler.domain.services.HarnessRule
import dev.ostrom.server.compiler.domain.services.HarnessStamp
import dev.ostrom.server.compiler.domain.usecases.WorkflowDecisionSigner
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal class SettableClock(var now: Instant = NOW) : Clock {
    override fun now(): Instant = now
}

/** The compiler's storage in memory, one map per organization. */
internal class MemoryCompilerRepository : CompilerRepository {
    val workflows = mutableMapOf<Pair<Uuid, String>, StoredWorkflow>()
    val decisions = mutableMapOf<Uuid, MutableList<DecisionDoc>>()
    val harnesses = mutableMapOf<Triple<Uuid, String?, String>, HarnessStamp>()
    val packages = mutableMapOf<Pair<Uuid, String>, StoredPackage>()
    val pins = mutableMapOf<Pair<Uuid, String?>, PackagePin>()
    val audits = mutableMapOf<Uuid, MutableList<PackageAudit>>()

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
        val current = workflows[orgId to id]
        if (expectedVersion != null && current?.version != expectedVersion) return null
        val saved = StoredWorkflow(CompilerDocs.workflow(raw), (current?.version ?: 0) + 1, author.by, author.at)
        workflows[orgId to id] = saved
        return saved
    }

    override suspend fun deleteWorkflow(orgId: Uuid, id: String): Boolean = workflows.remove(orgId to id) != null

    override suspend fun decisions(orgId: Uuid): List<DecisionDoc> = decisions[orgId].orEmpty()

    override suspend fun addDecision(orgId: Uuid, decision: DecisionDoc) {
        decisions.getOrPut(orgId) { mutableListOf() } += decision
    }

    override suspend fun harness(orgId: Uuid, repo: String?, digest: String): HarnessStamp? =
        harnesses[Triple(orgId, repo, digest)]

    override suspend fun saveHarness(orgId: Uuid, repo: String?, digest: String, stamp: HarnessStamp): HarnessStamp =
        harnesses.getOrPut(Triple(orgId, repo, digest)) { stamp }

    override suspend fun findPackage(orgId: Uuid, digest: String): StoredPackage? =
        packages.filterKeys { it.first == orgId }.values
            .firstOrNull { it.envelope.digest == digest || it.envelope.contentDigest == digest }

    override suspend fun packages(orgId: Uuid, repo: String?): List<StoredPackage> =
        packages.filterKeys { it.first == orgId }.values
            .filter { repo == null || it.envelope.repo == repo }
            .sortedByDescending { it.compiledAt }

    override suspend fun savePackage(orgId: Uuid, stored: StoredPackage): StoredPackage =
        packages.getOrPut(orgId to stored.envelope.contentDigest) { stored }

    override suspend fun updatePackage(orgId: Uuid, stored: StoredPackage) {
        packages[orgId to stored.envelope.contentDigest] = stored
    }

    override suspend fun pin(orgId: Uuid, repo: String?): PackagePin? = pins[orgId to repo]

    override suspend fun setPin(orgId: Uuid, repo: String?, pin: PackagePin?) {
        if (pin == null) pins.remove(orgId to repo) else pins[orgId to repo] = pin
    }

    override suspend fun audit(orgId: Uuid, line: PackageAudit) {
        audits.getOrPut(orgId) { mutableListOf() } += line
    }

    override suspend fun auditLog(orgId: Uuid, limit: Int): List<PackageAudit> =
        audits[orgId].orEmpty().reversed().take(limit)
}

internal class FixedSources(
    var rules: List<RuleDoc> = emptyList(),
    var ruleDecisions: List<DecisionDoc> = emptyList(),
    var repos: List<String> = listOf("acme-api"),
    var settings: CompilerSettings = CompilerSettings(),
) : CompilerSources {
    override suspend fun rules(orgId: Uuid): List<RuleDoc> = rules
    override suspend fun ruleDecisions(orgId: Uuid): List<DecisionDoc> = ruleDecisions
    override suspend fun repos(orgId: Uuid): List<String> = repos
    override suspend fun settings(orgId: Uuid): CompilerSettings = settings
}

internal class CountingHarnessSigner : HarnessReceiptSigner {
    val signed = mutableListOf<Pair<String, List<HarnessRule>>>()

    override suspend fun sign(
        orgId: Uuid,
        version: String,
        digest: String,
        rules: List<HarnessRule>,
        at: Instant,
    ): String {
        signed += version to rules
        return "rcpt_harness_${signed.size}"
    }
}

internal class RecordingDecisionSigner : WorkflowDecisionSigner {
    val signed = mutableListOf<Triple<String, JsonObject?, JsonObject>>()

    override suspend fun sign(
        orgId: Uuid,
        decision: DecisionDoc,
        action: String,
        before: JsonObject?,
        after: JsonObject,
    ): String {
        signed += Triple(action, before, after)
        return "rcpt_wf_${signed.size}"
    }
}
