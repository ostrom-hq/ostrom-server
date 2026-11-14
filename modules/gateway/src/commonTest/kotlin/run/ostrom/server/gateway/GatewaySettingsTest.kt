// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway

import run.ostrom.server.gateway.domain.GatewayInstanceSettings
import run.ostrom.server.gateway.domain.GatewayRules
import run.ostrom.server.gateway.domain.GatewaySettings
import run.ostrom.server.gateway.domain.GetGatewaySettingsUseCaseImpl
import run.ostrom.server.gateway.domain.GetModelPolicyUseCaseImpl
import run.ostrom.server.gateway.domain.InferenceProvider
import run.ostrom.server.gateway.domain.InvalidGatewaySettingsException
import run.ostrom.server.gateway.domain.ModelPolicy
import run.ostrom.server.gateway.domain.PolicyModelAccess
import run.ostrom.server.gateway.domain.ProviderEndpoints
import run.ostrom.server.gateway.domain.PutGatewaySettingsUseCaseImpl
import run.ostrom.server.gateway.domain.PutModelPolicyUseCaseImpl
import run.ostrom.server.gateway.domain.modelNotAllowed
import run.ostrom.server.kernel.settings.SettingsValues
import run.ostrom.server.secrets.domain.AdminRequiredException
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
    fun aMembersMaxConcurrentIsZeroForUnlimitedToThirtyTwoByMemberIdInOneSpelling() = runTest {
        val put = PutGatewaySettingsUseCaseImpl(repository)
        val member = Gw.memberA.memberId
        val upper = member.toString().uppercase()
        val saved = put(Gw.adminA, GatewaySettings(memberConcurrency = mapOf(upper to 1)))
        assertEquals(mapOf(member.toString() to 1), saved.memberConcurrency)
        assertEquals(saved, GetGatewaySettingsUseCaseImpl(repository)(Gw.adminA))
        val most = put(Gw.adminA, GatewaySettings(memberConcurrency = mapOf("$member" to 32)))
        assertEquals(mapOf("$member" to 32), most.memberConcurrency)
        val tooMany = (0..GatewayRules.MAX_MEMBERS).associate { Uuid.random().toString() to 1 }
        val unlimited = put(Gw.adminA, GatewaySettings(memberConcurrency = mapOf("$member" to 0)))
        assertEquals(mapOf("$member" to 0), unlimited.memberConcurrency, "0 is unlimited, as TeamCode")
        for (bad in listOf(mapOf("$member" to -1), mapOf("$member" to 33), mapOf("ada" to 2), tooMany)) {
            assertFailsWith<InvalidGatewaySettingsException> {
                put(Gw.adminA, GatewaySettings(memberConcurrency = bad))
            }
        }
    }

    @Test
    fun aLaunchReadsTheSamePolicyAsAModelCall() = runTest {
        val access = PolicyModelAccess(repository)
        assertNull(access.refusal(Gw.orgA, Gw.memberA.memberId, "gpt-5"), "No policy: every model")
        repository.putPolicy(
            Gw.orgA,
            ModelPolicy(allowed = listOf("claude-"), byMember = mapOf("${Gw.otherA.memberId}" to listOf("gpt-"))),
        )
        assertNull(access.refusal(Gw.orgA, Gw.memberA.memberId, "claude-opus-4-1"), "The org's list, by prefix")
        assertEquals(
            "Your organization does not allow the model gpt-5.",
            access.refusal(Gw.orgA, Gw.memberA.memberId, "gpt-5"),
        )
        assertNull(access.refusal(Gw.orgA, Gw.otherA.memberId, "gpt-5"), "The member's own list replaces the org's")
        assertEquals(modelNotAllowed("claude-opus-4-1"), access.refusal(Gw.orgA, Gw.otherA.memberId, "claude-opus-4-1"))
        assertNull(access.refusal(Gw.orgB, Gw.adminB.memberId, "gpt-5"), "Each org its own")
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
            // An empty own list would allow every model to a member the org restricts.
            ModelPolicy(allowed = listOf("claude-haiku"), byMember = mapOf(Uuid.random().toString() to emptyList())),
            ModelPolicy(
                byMember = mapOf(
                    Gw.otherA.memberId.toString() to listOf("a"),
                    Gw.otherA.memberId.toString().uppercase() to listOf("b"),
                ),
            ),
        )
        for (bad in refused) assertFailsWith<InvalidGatewaySettingsException>(bad.toString()) { put(Gw.adminA, bad) }
        assertEquals(policy, get(Gw.memberA), "Nothing stored on a refusal")
    }

    @Test
    fun aMemberIdWrittenInAnotherFormStillRestrictsThatMember() = runTest {
        val written = Gw.otherA.memberId.toString().uppercase()
        val stored = PutModelPolicyUseCaseImpl(repository)(
            Gw.adminA,
            ModelPolicy(byMember = mapOf(written to listOf("claude-haiku"))),
        )
        assertEquals(setOf(Gw.otherA.memberId.toString()), stored.byMember.keys)
        assertFalse(GetModelPolicyUseCaseImpl(repository)(Gw.otherA).allows(Gw.otherA.memberId, "claude-opus-5"))
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
    fun theInstanceSettingsSetKeysAndProviderUrls() {
        val defaults = GatewayInstanceSettings.providers(SettingsValues.EMPTY)
        assertEquals(ProviderEndpoints(), defaults.endpoints)
        assertNull(defaults.keys.of(InferenceProvider.ANTHROPIC))
        val custom = GatewayInstanceSettings.providers(
            SettingsValues(
                mapOf(
                    "anthropicApiKey" to "sk-an",
                    "openaiApiKey" to "sk-op",
                    "openrouterApiKey" to "sk-or",
                    "anthropicUrl" to "http://proxy.local:8080/",
                    "codexUrl" to "https://codex.local/backend",
                ),
            ),
        )
        assertEquals("http://proxy.local:8080", custom.endpoints.anthropic)
        assertEquals("https://codex.local/backend", custom.endpoints.codex)
        assertEquals("https://api.openai.com", custom.endpoints.openai)
        assertEquals("sk-an", custom.keys.anthropic)
        assertEquals("sk-op", custom.keys.openai)
        assertEquals("sk-or", custom.keys.openrouter)
        assertFalse("sk-an" in custom.keys.toString(), "Keys never printed")
    }

    @Test
    fun theConsolesGatewaySectionSealsTheKeysAndChecksTheUrls() {
        val section = GatewayInstanceSettings.SECTION
        assertEquals("gateway", section.name)
        assertEquals(
            listOf(
                "anthropicApiKey" to "OSTROM_GATEWAY_ANTHROPIC_API_KEY",
                "openaiApiKey" to "OSTROM_GATEWAY_OPENAI_API_KEY",
                "openrouterApiKey" to "OSTROM_GATEWAY_OPENROUTER_API_KEY",
                "anthropicUrl" to "OSTROM_GATEWAY_ANTHROPIC_URL",
                "openaiUrl" to "OSTROM_GATEWAY_OPENAI_URL",
                "openrouterUrl" to "OSTROM_GATEWAY_OPENROUTER_URL",
                "codexUrl" to "OSTROM_GATEWAY_CODEX_URL",
            ),
            section.fields.map { it.name to it.legacyVariable },
        )
        assertEquals(listOf(true, true, true, false, false, false, false), section.fields.map { it.secret })
        val key = checkNotNull(section.field("anthropicApiKey"))
        assertNull(key.check("sk-ant-api03-x"))
        val url = checkNotNull(section.field("openrouterUrl"))
        assertNull(url.check("http://127.0.0.1:4620"))
        assertNull(url.check("https://openrouter.ai/api"))
        assertEquals("A provider's base URL is an http or https URL.", url.check("ftp://x"))
    }
}
