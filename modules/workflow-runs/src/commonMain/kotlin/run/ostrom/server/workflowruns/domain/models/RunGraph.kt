// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns.domain.models

import run.ostrom.core.api.models.WorkflowEdge
import run.ostrom.core.api.models.WorkflowNode
import kotlinx.serialization.Serializable

/**
 * The graph a run plays: the workflow's nodes and edges as they were when the run started (the contract: "the
 * definition is copied at start"), so that an edit of the workflow never changes a run under way.
 *
 * @property start The node the run starts at; the first node when absent.
 * @property nodes The nodes.
 * @property edges The edges; an edge's `on` is the outcome that takes it, `next` when absent.
 */
@Serializable
data class RunGraph(
    val start: String? = null,
    val nodes: List<WorkflowNode> = emptyList(),
    val edges: List<WorkflowEdge> = emptyList(),
)
