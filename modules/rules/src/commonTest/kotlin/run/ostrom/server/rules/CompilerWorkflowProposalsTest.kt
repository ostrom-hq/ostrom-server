// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.rules

import run.ostrom.core.api.models.GateAction
import run.ostrom.core.api.models.NodeSuggestions
import run.ostrom.core.api.models.StringSuggestion
import run.ostrom.core.api.models.Workflow
import run.ostrom.core.api.models.WorkflowEdge
import run.ostrom.core.api.models.WorkflowNode
import run.ostrom.core.api.models.WorkflowNodeKind
import run.ostrom.core.api.models.WorkflowStatus
import run.ostrom.server.compiler.domain.repositories.MAX_WORKFLOWS
import run.ostrom.server.rules.domain.models.GatedRule
import run.ostrom.server.rules.domain.models.LearnedWorkflow
import run.ostrom.server.rules.domain.models.ProcessCommand
import run.ostrom.server.rules.domain.models.ProcessNode
import run.ostrom.server.rules.domain.models.ProcessNodeKind
import run.ostrom.server.rules.domain.models.ProcessProposal
import run.ostrom.server.rules.domain.models.RuleGate
import run.ostrom.server.rules.domain.models.RuleGateAction
import run.ostrom.server.rules.domain.models.RuleSource
import run.ostrom.server.rules.fakes.FakeWorkflows
import run.ostrom.server.rules.fakes.FakeWorkflowsRepository
import run.ostrom.server.rules.fakes.RulesFixtures
import run.ostrom.server.rules.infrastructure.CompilerWorkflowProposals
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CompilerWorkflowProposalsTest {
    private val org = RulesFixtures.orgA
    private val at = RulesFixtures.epoch
    private val repository = FakeWorkflowsRepository()
    private val checks = FakeWorkflows()
    private val proposals = CompilerWorkflowProposals(repository, checks)

    private val rule = GatedRule(
        "rule_learned_main",
        RuleGate(
            listOf(RuleGateAction("push-main", "Push to main", emptyList(), listOf("api"), null)),
            listOf("lyes"),
            "x",
        ),
    )
    private val process = ProcessProposal(
        key = "process:api:pull-request",
        name = "Pull request checks (api)",
        description = "1 CI job on each pull request.",
        repo = "acme/api",
        nodes = listOf(
            ProcessNode("n1", ProcessNodeKind.TRIGGER, "A pull request is opened or updated"),
            ProcessNode("n2", ProcessNodeKind.STEP, "Docs"),
            ProcessNode("n3", ProcessNodeKind.CHECK, "test", ProcessCommand("make test", "ci.yml:5")),
            ProcessNode("n4", ProcessNodeKind.GATE, "A yes before merging", rule = rule),
            ProcessNode("n5", ProcessNodeKind.NOTIFY, "Tell the team on Slack"),
        ),
        sources = listOf(RuleSource("ci", "ci.yml:3", "https://github.com/acme/api/blob/main/ci.yml#L3")),
    )

    private val found = "2026-10-30T12:00:00.000Z"
    private val nodes = listOf(
        WorkflowNode("n1", WorkflowNodeKind.TRIGGER, "A pull request is opened or updated"),
        WorkflowNode("n2", WorkflowNodeKind.STEP, "Docs"),
        WorkflowNode(
            "n3", WorkflowNodeKind.CHECK, "test",
            suggested = NodeSuggestions(command = StringSuggestion("make test", listOf("ci.yml:5"), at = found)),
        ),
        WorkflowNode(
            "n4", WorkflowNodeKind.GATE, "A yes before merging",
            approvers = listOf("lyes"),
            approverReason = "x",
            ruleIds = listOf("rule_learned_main"),
            actions = listOf(GateAction("push-main", "Push to main", emptyList(), listOf("api"), null)),
        ),
        WorkflowNode("n5", WorkflowNodeKind.NOTIFY, "Tell the team on Slack"),
    )

    @Test
    fun aProposalIsStoredProposedWithoutADecisionAndOnlyUnderAFreeId() = runTest {
        val existing = LearnedWorkflow("wf_learned_b", process.copy(name = "Kept"))
        assertEquals(1, proposals.propose(org, listOf(existing), at))
        val stored = proposals.propose(org, listOf(LearnedWorkflow("wf_learned_a", process), existing), at)
        assertEquals(1, stored, "The one that exists is left as it is")
        assertEquals("Kept", repository.workflows.getValue(org to "wf_learned_b").doc.workflow.name)
        assertEquals(3, checks.checked.size, "Each passes the console's checks")
        val saved = repository.workflows.getValue(org to "wf_learned_a")
        assertEquals("analysis", saved.updatedBy)
        assertEquals(
            Workflow(
                id = "wf_learned_a",
                name = "Pull request checks (api)",
                domain = "Processes",
                description = "1 CI job on each pull request.",
                repos = listOf("acme/api"),
                nodes = nodes,
                edges = listOf(
                    WorkflowEdge("n1", "n2"), WorkflowEdge("n2", "n3"), WorkflowEdge("n3", "n4"),
                    WorkflowEdge("n4", "n5"), WorkflowEdge("n5", null),
                ),
                sources = listOf(
                    run.ostrom.core.api.models.RuleSource(
                        "ci", "ci.yml:3", "https://github.com/acme/api/blob/main/ci.yml#L3",
                    ),
                ),
                status = WorkflowStatus.PROPOSED,
                foundAt = found,
                updatedAt = found,
            ),
            saved.doc.workflow,
        )
    }

    @Test
    fun nothingIsStoredPastTheOrganizationsLimit() = runTest {
        repository.count = MAX_WORKFLOWS
        assertEquals(0, proposals.propose(org, listOf(LearnedWorkflow("wf_learned_a", process)), at))
        assertEquals(emptyMap(), repository.workflows)
    }
}
