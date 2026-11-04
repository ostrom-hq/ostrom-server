// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.infrastructure

import dev.ostrom.core.api.models.PackageMode
import dev.ostrom.server.compiler.domain.repositories.CompilerRepository
import dev.ostrom.server.kernel.http.OstromJson
import dev.ostrom.server.workflowruns.domain.models.PackageNode
import dev.ostrom.server.workflowruns.domain.models.PackageNotify
import dev.ostrom.server.workflowruns.domain.models.PackageScript
import dev.ostrom.server.workflowruns.domain.models.RunPackage
import dev.ostrom.server.workflowruns.domain.services.RunPackages
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [RunPackages] on the compiler module's packages: the payload of the signed envelope is the package's body, read for
 * its nodes, scripts and notifications only.
 *
 * @param compiler The compiler's repository.
 */
class CompilerRunPackages(private val compiler: CompilerRepository) : RunPackages {
    override suspend fun find(orgId: Uuid, digest: String): RunPackage? {
        val stored = compiler.findPackage(orgId, digest) ?: return null
        val envelope = stored.envelope
        val body = OstromJson.decodeFromString(BodyView.serializer(), envelope.payload)
        return RunPackage(
            envelope = envelope,
            nodes = body.nodes.map { PackageNode(it.id, it.workflowId, it.workflowName, it.kind, it.label, it.mode) },
            scripts = body.scripts.map { PackageScript(it.id, it.nodeId, it.sha256, it.mode) },
            notifications = body.notifications.map { PackageNotify(it.id, it.nodeId, it.channel, it.message, it.mode) },
            compiledAt = stored.compiledAt,
        )
    }

    override suspend fun current(orgId: Uuid, pack: RunPackage, startedAt: Instant): Boolean {
        val own = pack.envelope
        if (compiler.pin(orgId, own.repo)?.contentDigest == own.contentDigest) return true
        val ownAt = Instant.parse(pack.compiledAt)
        // Replaced: another usable package of the repository, compiled after this one and before the session started.
        return compiler.packages(orgId).none { other ->
            val at = Instant.parse(other.compiledAt)
            other.envelope.repo == own.repo && !other.revoked && at > ownAt && at <= startedAt
        }
    }
}

// What the runs read of a package body (`ostrom-core` `SessionPackage`); the rest is ignored.

@Serializable
internal data class BodyView(
    val nodes: List<NodeView> = emptyList(),
    val scripts: List<ScriptView> = emptyList(),
    val notifications: List<NotifyView> = emptyList(),
)

@Serializable
internal data class NodeView(
    val id: String,
    val workflowId: String,
    val workflowName: String = "",
    val kind: String,
    val label: String = "",
    val mode: PackageMode = PackageMode.ENFORCE,
)

@Serializable
internal data class ScriptView(val id: String, val nodeId: String, val sha256: String, val mode: PackageMode)

@Serializable
internal data class NotifyView(
    val id: String,
    val nodeId: String,
    val channel: String,
    val message: String,
    val mode: PackageMode,
)
