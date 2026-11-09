// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.mcprelay

import run.ostrom.server.mcprelay.domain.McpRelayRepository
import run.ostrom.server.mcprelay.domain.UpstreamTarget
import run.ostrom.server.mcprelay.domain.scrubbed
import run.ostrom.server.secrets.domain.SecretValue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

/** Small guarantees of the relay's domain: what is printed, what is scrubbed, what the defaults read. */
class RelayDomainTest {
    @Test
    fun aTargetNeverPrintsItsSecrets() {
        val target = UpstreamTarget(
            Relay.server(),
            SecretValue("credential_value"),
            mapOf(
                "A" to SecretValue("env_value"),
            ),
            "k",
        )
        val printed = target.toString()
        assertFalse("credential_value" in printed || "env_value" in printed, printed)
        assertEquals(listOf("credential_value", "env_value"), target.secretValues)
    }

    @Test
    fun valuesTooShortToBeSecretsLeaveTheTreeAsItIs() {
        val tree = buildJsonObject { put("text", "abc") }
        assertSame(tree, tree.scrubbed(listOf("abc")))
        assertEquals(JsonPrimitive(3), JsonPrimitive(3).scrubbed(listOf("a_long_secret")))
    }

    @Test
    fun theJournalIsReadFromEveryServerAndMemberByDefault() = runTest {
        val repository: McpRelayRepository = InMemoryRelayRepository()
        assertEquals(emptyList(), repository.listCalls(Relay.orgA, null, 10))
    }
}
