// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches

import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.launches.domain.models.AgentVerdict
import dev.ostrom.server.launches.domain.usecases.CheckAgentPolicyUseCaseImpl
import dev.ostrom.server.launches.domain.usecases.LaunchPolicy
import dev.ostrom.server.launches.domain.models.OtherDeveloperForbiddenException
import dev.ostrom.server.launches.domain.models.UnknownDeveloperException
import dev.ostrom.server.launches.fakes.ADA
import dev.ostrom.server.launches.fakes.FakeLaunchers
import dev.ostrom.server.launches.fakes.FakeModelRules
import dev.ostrom.server.launches.fakes.ORG
import dev.ostrom.server.launches.fakes.OTHER_ORG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class CheckAgentPolicyTest {
    private val rules = FakeModelRules(mapOf("gpt-5" to "gpt-5 is not on the allowlist."))
    private val bob = ADA.copy(userId = Uuid.random(), login = "bob", role = OrgRole.BILLING)
    private val eve = ADA.copy(orgId = OTHER_ORG, userId = Uuid.random(), login = "eve")
    private val check = CheckAgentPolicyUseCaseImpl(
        LaunchPolicy(rules),
        FakeLaunchers(listOf(ADA, bob, eve).associateBy { it.userId.toString() }),
    )

    @Test
    fun anOwnerOrAnAdminReadsAnotherDevelopersVerdictInTheirOrganization() = runTest {
        for (role in listOf(OrgRole.OWNER, OrgRole.ADMIN)) {
            val admin = ADA.copy(role = role)
            assertEquals(AgentVerdict(false, LaunchPolicy.BILLING_ONLY), check(admin, "codex", null, "bob"), role.name)
        }
        check(ADA, "codex", "gpt-5", "ADA")
        assertEquals(listOf(Triple(ORG, "ada", "gpt-5")), rules.asked, "Their own login, in any case, is the caller")
    }

    @Test
    fun aMemberReadsTheirOwnVerdictOnlyAndLearnsNothingOfTheOthers() = runTest {
        assertEquals(AgentVerdict(allowed = true), check(ADA, "codex", null, "ada"))
        for (dev in listOf("bob", "eve", "nobody")) {
            assertFailsWith<OtherDeveloperForbiddenException> { check(ADA, "codex", null, dev) }
        }
    }

    @Test
    fun anAdminNamingSomeoneOutsideTheOrganizationGetsUnknown() = runTest {
        val admin = ADA.copy(role = OrgRole.ADMIN)
        assertFailsWith<UnknownDeveloperException> { check(admin, "codex", null, "eve") }
        assertFailsWith<UnknownDeveloperException> { check(admin, "codex", null, "nobody") }
    }

    @Test
    fun everyWellFormedAgentIsAllowedWithAModelTheOrganizationAllows() = runTest {
        assertEquals(AgentVerdict(allowed = true), check(ADA, "claude-code", "claude-opus-4-1[1m]"))
        assertEquals(listOf(Triple(ORG, "ada", "claude-opus-4-1[1m]")), rules.asked, "The person is the launcher's")
        assertEquals(AgentVerdict(allowed = true), check(ADA, "codex", "anthropic/claude-sonnet-4.5"))
    }

    @Test
    fun aLaunchThatNamesNothingIsAllowedWithoutAskingTheModelRules() = runTest {
        assertEquals(AgentVerdict(allowed = true), check(ADA, null, null))
        assertEquals(AgentVerdict(allowed = true), check(ADA, "pi", null))
        assertTrue(rules.asked.isEmpty())
    }

    @Test
    fun aModelAloneIsCheckedAgainstTheRules() = runTest {
        assertEquals(AgentVerdict(allowed = true), check(ADA, null, "claude-haiku-4-5"))
        assertEquals(AgentVerdict(false, "gpt-5 is not on the allowlist."), check(ADA, null, "gpt-5"))
    }

    @Test
    fun aMalformedAgentOrModelIsRefusedWithItsReason() = runTest {
        assertEquals(AgentVerdict(false, "The agent name is not valid."), check(ADA, "Claude Code", "claude"))
        assertEquals(AgentVerdict(false, "The agent name is not valid."), check(ADA, "a".repeat(65), null))
        assertEquals(AgentVerdict(false, "The model name is not valid."), check(ADA, "codex", "model; rm -rf /"))
        assertEquals(AgentVerdict(false, "The model name is not valid."), check(ADA, "codex", "m".repeat(201)))
        assertTrue(rules.asked.isEmpty(), "A malformed name never reaches the rules")
    }

    @Test
    fun aBillingMemberRunsNoAgentAndEveryOtherRoleDoes() = runTest {
        val billing = check(ADA.copy(role = OrgRole.BILLING), "codex", "gpt-5")
        assertEquals(AgentVerdict(false, "A billing member does not run agents."), billing)
        for (role in OrgRole.entries - OrgRole.BILLING) {
            assertEquals(AgentVerdict(allowed = true), check(ADA.copy(role = role), "codex", null), role.name)
        }
    }

    @Test
    fun aModelTheOrganizationRefusesStopsTheLaunch() = runTest {
        assertEquals(AgentVerdict(false, "gpt-5 is not on the allowlist."), check(ADA, "codex", "gpt-5"))
    }

    @Test
    fun theNamesFollowTheCliAgentsAndTheModelsItPasses() {
        for (agent in listOf("claude-code", "codex", "opencode", "gemini", "grok", "hermes", "pi")) {
            assertTrue(LaunchPolicy.AGENT_NAME.matches(agent), agent)
        }
        assertFalse(LaunchPolicy.AGENT_NAME.matches("-codex"))
        assertTrue(LaunchPolicy.MODEL_NAME.matches("gpt-5.1-codex"))
        assertFalse(LaunchPolicy.MODEL_NAME.matches(""))
    }
}
