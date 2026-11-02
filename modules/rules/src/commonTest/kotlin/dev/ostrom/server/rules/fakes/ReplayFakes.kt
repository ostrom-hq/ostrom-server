// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.fakes

import dev.ostrom.server.gates.domain.models.AskedVia
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateMode
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.GateSubject
import dev.ostrom.server.gates.domain.repositories.GateHistory
import dev.ostrom.server.rules.domain.models.Rule
import dev.ostrom.server.rules.domain.models.RuleGate
import dev.ostrom.server.rules.domain.models.RuleGateAction
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Boundary actions and gated rules to replay. */
object ReplayFixtures {
    /**
     * A boundary action as its gate recorded it.
     *
     * @param orgId Its organization.
     * @param subject What was asked.
     * @param askedBy Who asked.
     * @param hoursAgo How long before the fixtures' epoch.
     * @param mode How it was gated.
     */
    fun action(
        orgId: Uuid = RulesFixtures.orgA,
        subject: GateSubject = push(),
        askedBy: List<String> = listOf("lyes"),
        hoursAgo: Int = 1,
        mode: GateMode = GateMode.SHADOW,
    ) = Gate(
        id = Uuid.random(),
        orgId = orgId,
        key = "k-${subject.actionClass}-$hoursAgo",
        subject = subject,
        node = null,
        label = subject.actionClass,
        mode = mode,
        status = GateStatus.PENDING,
        askedBy = askedBy,
        askedVia = setOf(AskedVia.HOOK),
        sessionIds = listOf("ses_1"),
        shownDigest = "d",
        evidence = emptyList(),
        createdAt = RulesFixtures.epoch - hoursAgo.hours,
    )

    /**
     * A subject.
     *
     * @param repo The repository, `owner/name`.
     * @param cls The action class.
     * @param command The command.
     */
    fun push(repo: String? = "acme/api", cls: String = "push-main", command: String = "git push origin main") =
        GateSubject(repo = repo, actionClass = cls, command = command, scope = "session:ses_1")

    /**
     * A rule with a gate.
     *
     * @param id Its id.
     * @param orgId Its organization.
     * @param approvers Who may say yes.
     * @param cls The action class it gates.
     * @param patterns The commands it gates too.
     * @param repos The repositories its action names.
     * @param scope Its scope.
     */
    @Suppress("LongParameterList")
    fun gated(
        id: String = "rule_gate",
        orgId: Uuid = RulesFixtures.orgA,
        approvers: List<String> = listOf("bob"),
        cls: String = "push-main",
        patterns: List<String> = emptyList(),
        repos: List<String> = emptyList(),
        scope: List<String> = emptyList(),
    ): Rule = RulesFixtures.rule(id = id, orgId = orgId, scope = scope).copy(
        gate = RuleGate(listOf(RuleGateAction(cls, "Push", patterns, repos, null)), approvers, null),
    )
}

/** The gates' history, in memory, by organization. */
class FakeGateHistory(private val gates: List<Gate> = emptyList()) : GateHistory {
    val reads = mutableListOf<Triple<Uuid, Instant, Int>>()

    override suspend fun recent(orgId: Uuid, since: Instant, limit: Int): List<Gate> {
        reads += Triple(orgId, since, limit)
        return gates.filter { it.orgId == orgId && it.createdAt >= since }.sortedByDescending { it.createdAt }.take(limit)
    }
}
