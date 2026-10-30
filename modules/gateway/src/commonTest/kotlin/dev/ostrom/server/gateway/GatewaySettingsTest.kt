// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway

import dev.ostrom.server.gateway.domain.GatewayRules
import dev.ostrom.server.gateway.domain.GatewaySettings
import dev.ostrom.server.gateway.domain.GetGatewaySettingsUseCaseImpl
import dev.ostrom.server.gateway.domain.GetModelPolicyUseCaseImpl
import dev.ostrom.server.gateway.domain.InferenceProvider
import dev.ostrom.server.gateway.domain.InvalidGatewaySettingsException
import dev.ostrom.server.gateway.domain.ModelPolicy
import dev.ostrom.server.gateway.domain.ProviderEndpoints
import dev.ostrom.server.gateway.domain.PutGatewaySettingsUseCaseImpl
import dev.ostrom.server.gateway.domain.PutModelPolicyUseCaseImpl
import dev.ostrom.server.kernel.config.InvalidConfigException
import dev.ostrom.server.kernel.config.MapConfigSource
import dev.ostrom.server.secrets.domain.AdminRequiredException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Settings and model policy: admins change them, keys are named by secret, policies are bounded. */
class GatewaySettingsTest {
    private val repository = InMemorySettings()

    @Test
    fun onlyAdminsReadAndChangeTheSettings() = runTest {
        val put = PutGatewaySettingsUseCaseImpl(repository)
        val get = GetGatewaySettingsUseCaseImpl(repository)
        assertEquals(GatewaySettings(), get(Gw.adminA))
        val settings = GatewaySettings("anthropic-key", "openai.key", "router_key", useSharedAccounts = false)
        assertEquals(settings, put(Gw.adminA, settings))
        assertEquals(settings, get(Gw.adminA))
        assertEquals(GatewaySettings(), get(Gw.adminB), "Each org its own")
        assertFailsWith<AdminRequiredException> { get(Gw.memberA) }
        assertFailsWith<AdminRequiredException> { put(Gw.memberA, settings) }
        for (bad in listOf("sk-ant-api03-a key?", "", "x".repeat(129))) {
            assertFailsWith<InvalidGatewaySettingsException>(bad) {
                put(Gw.adminA, GatewaySettings(openaiKeySecret = bad))
            }
        }
    }

    @Test
    fun membersReadThePolicyAdminsChangeItWithinBounds() = runTest {
        val put = PutModelPolicyUseCaseImpl(repository)
        val get = GetModelPolicyUseCaseImpl(repository)
        assertEquals(ModelPolicy(), get(Gw.memberA))
        val policy = ModelPolicy(
            allowed = listOf("claude-", "gpt-5"),
            default = "claude-sonnet-5",
            byMember = mapOf(Gw.memberA.memberId.toString() to listOf("meta/llama")),
        )
        assertEquals(policy, put(Gw.adminA, policy))
        assertEquals(policy, get(Gw.memberA))
        assertFailsWith<AdminRequiredException> { put(Gw.memberA, policy) }
        val tooManyMembers = (0..GatewayRules.MAX_MEMBERS).associate { Uuid.random().toString() to listOf("m") }
        val refused = listOf(
            ModelPolicy(byMember = tooManyMembers),
            ModelPolicy(byMember = mapOf("not-a-member" to listOf("m"))),
            ModelPolicy(allowed = List(GatewayRules.MAX_MODELS + 1) { "m$it" }),
            ModelPolicy(allowed = listOf("has space")),
            ModelPolicy(default = ""),
            ModelPolicy(byMember = mapOf(Uuid.random().toString() to listOf("bad model"))),
        )
        for (bad in refused) assertFailsWith<InvalidGatewaySettingsException>(bad.toString()) { put(Gw.adminA, bad) }
        assertEquals(policy, get(Gw.memberA), "Nothing stored on a refusal")
    }

    @Test
    fun aPolicyAllowsByPrefixAndAMemberListReplacesTheOrgOne() {
        val member = Gw.memberA.memberId
        val policy = ModelPolicy(
            allowed = listOf("claude-"),
            byMember = mapOf(
                Gw.otherA.memberId.toString() to emptyList(),
            ),
        )
        assertTrue(policy.allows(member, "claude-opus-5"))
        assertFalse(policy.allows(member, "gpt-5"))
        assertTrue(policy.allows(Gw.otherA.memberId, "gpt-5"), "An empty list allows every model")
        assertTrue(ModelPolicy().allows(member, "anything"))
    }

    @Test
    fun theInstanceConfigurationSetsKeysAndProviderUrls() {
        val defaults = GatewayConfig.from(MapConfigSource(emptyMap()))
        assertEquals(ProviderEndpoints(), defaults.endpoints)
        assertNull(defaults.keys.of(InferenceProvider.ANTHROPIC))
        val custom = GatewayConfig.from(
            MapConfigSource(
                mapOf(
                    "OSTROM_GATEWAY_ANTHROPIC_API_KEY" to "sk-an",
                    "OSTROM_GATEWAY_OPENAI_API_KEY" to "sk-op",
                    "OSTROM_GATEWAY_OPENROUTER_API_KEY" to "sk-or",
                    "OSTROM_GATEWAY_ANTHROPIC_URL" to "http://proxy.local:8080/",
                    "OSTROM_GATEWAY_CODEX_URL" to "https://codex.local/backend",
                ),
            ),
        )
        assertEquals("http://proxy.local:8080", custom.endpoints.anthropic)
        assertEquals("https://codex.local/backend", custom.endpoints.codex)
        assertEquals("https://api.openai.com", custom.endpoints.openai)
        assertEquals("sk-or", custom.keys.openrouter)
        val error = assertFailsWith<InvalidConfigException> {
            GatewayConfig.from(MapConfigSource(mapOf("OSTROM_GATEWAY_OPENROUTER_URL" to "ftp://x")))
        }
        assertEquals("OSTROM_GATEWAY_OPENROUTER_URL must be an http(s) URL.", error.message)
        assertFalse("sk-an" in custom.toString(), "Keys never printed")
    }
}
