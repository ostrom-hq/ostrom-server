// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.launches

import run.ostrom.server.kernel.auth.ModelAccess
import run.ostrom.server.launches.fakes.ADA
import run.ostrom.server.launches.fakes.FakeLaunchers
import run.ostrom.server.launches.fakes.memberOf
import run.ostrom.server.launches.infrastructure.ModelAccessLaunchRules
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ModelAccessLaunchRulesTest {
    /** Allows the models starting with `claude-`, and records who it was asked for. */
    private class ClaudeOnly : ModelAccess {
        val asked = mutableListOf<Triple<Uuid, Uuid, String>>()

        override suspend fun refusal(orgId: Uuid, memberId: Uuid, model: String): String? {
            asked += Triple(orgId, memberId, model)
            return if (model.startsWith("claude-")) null else "Your organization does not allow the model $model."
        }
    }

    private val access = ClaudeOnly()
    private val launchers = FakeLaunchers(mapOf("ada" to ADA))
    private val rules = ModelAccessLaunchRules(access, launchers)

    @Test
    fun aLaunchIsAskedForTheMemberTheGatewayChecks() = runTest {
        assertNull(rules.refusal(ADA, "claude-opus-4-1"))
        assertEquals("Your organization does not allow the model gpt-5.", rules.refusal(ADA, "gpt-5"))
        val member = memberOf(ADA.userId)
        val asked = listOf(Triple(ADA.orgId, member, "claude-opus-4-1"), Triple(ADA.orgId, member, "gpt-5"))
        assertEquals(asked, access.asked, "The member's id, as the gateway checks a call")
    }

    @Test
    fun someoneWhoLeftTheOrganizationLaunchesNothing() = runTest {
        launchers.roles[ADA.userId] = null
        assertEquals(ModelAccessLaunchRules.NOT_A_MEMBER, rules.refusal(ADA, "claude-opus-4-1"))
        assertTrue(access.asked.isEmpty(), "The policy is never read for someone who is not a member")
    }
}
