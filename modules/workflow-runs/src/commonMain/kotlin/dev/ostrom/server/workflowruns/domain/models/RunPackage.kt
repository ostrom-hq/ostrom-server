// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.domain.models

import dev.ostrom.core.api.models.PackageEnvelope
import dev.ostrom.core.api.models.PackageMode

/**
 * A compiled session package as the runs read it: its signed envelope, and the nodes, scripts and notifications of
 * its payload.
 *
 * @property envelope The signed envelope, as the compiler stored it.
 * @property nodes The workflow nodes.
 * @property scripts The check scripts.
 * @property notifications The notify nodes' messages.
 */
data class RunPackage(
    val envelope: PackageEnvelope,
    val nodes: List<PackageNode>,
    val scripts: List<PackageScript>,
    val notifications: List<PackageNotify>,
) {
    /** The package's digest. */
    val digest: String get() = envelope.digest

    /** Its version. */
    val version: String get() = envelope.version
}

/**
 * A workflow node of a package.
 *
 * @property id The node.
 * @property workflowId Its workflow.
 * @property workflowName The workflow's name.
 * @property kind `trigger`, `step`, `check`, `gate`, `notify` or `agent`.
 * @property label Its label.
 * @property mode Enforced or shadow.
 */
data class PackageNode(
    val id: String,
    val workflowId: String,
    val workflowName: String,
    val kind: String,
    val label: String,
    val mode: PackageMode,
)

/**
 * A check script of a package.
 *
 * @property id The script.
 * @property nodeId Its check node.
 * @property sha256 The digest of its body, which the launcher reports back.
 * @property mode Enforced or shadow.
 */
data class PackageScript(val id: String, val nodeId: String, val sha256: String, val mode: PackageMode)

/**
 * A notification of a package.
 *
 * @property id The notification.
 * @property nodeId Its notify node.
 * @property channel `log:<name>`, `slack:<channel>` or `discord:<name>`.
 * @property message Its text, with `{dev}`, `{repo}`, `{command}` and `{session}`.
 * @property mode Enforced or shadow.
 */
data class PackageNotify(
    val id: String,
    val nodeId: String,
    val channel: String,
    val message: String,
    val mode: PackageMode,
)
