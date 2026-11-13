// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.launches

import run.ostrom.server.gateway.GatewayModule
import run.ostrom.server.gateway.domain.GatewaySettingsRepository
import run.ostrom.server.gateway.domain.ModelPolicy
import run.ostrom.server.identity.domain.ApiToken
import run.ostrom.server.identity.domain.ApiTokensRepository
import run.ostrom.server.identity.domain.CLI_SCOPES
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.SecretHasher
import run.ostrom.server.identity.domain.TokenKind
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.launches.domain.repositories.LaunchesRepository
import run.ostrom.server.launches.presentation.AgentPolicyResponse
import run.ostrom.server.launches.presentation.LaunchResponse
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.Org
import run.ostrom.server.orgs.domain.OrgsRepository
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.sharing.SharingModule
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A launch answers what the inference gateway will answer: `ostrom claude` and the console's "Would a launch pass?"
 * refuse a model the organization's model policy refuses, on the real gateway, orgs and launches modules and
 * PostgreSQL.
 */
class LaunchModelPolicyPostgresTest {
    private val modules = launchModules(SecretsModule, SharingModule, GatewayModule)
    private val overrides = module { single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) } }
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    /** A member of a new organization, and the CLI token of theirs bound to it. */
    private class Person(val orgId: Uuid, val memberId: Uuid, val cliToken: String)

    private suspend fun TestServer.person(login: String): Person {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        val org = Org(Uuid.random(), "$login-team", "$login team", null, null, now)
        val member = Member(Uuid.random(), org.id, user.id, OrgRole.MEMBER, now)
        koin.get<OrgsRepository>().create(org, member) { false }
        val secret = "osc_" + Uuid.random().toHexString()
        val token = ApiToken(
            Uuid.random(), user.id, org.id, "cli", TokenKind.CLI, CLI_SCOPES, "hint", null, now, now + 1.hours, null,
            null,
        )
        koin.get<ApiTokensRepository>().create(token, koin.get<SecretHasher>().hash(secret))
        return Person(org.id, member.id, secret)
    }

    private suspend fun TestServer.verdict(person: Person, model: String): AgentPolicyResponse =
        client.get("/api/v1/policy/agents?agent=claude-code&model=$model") { bearerAuth(person.cliToken) }.body()

    private suspend fun TestServer.launch(person: Person, model: String): HttpResponse =
        client.post("/api/v1/launches") {
            bearerAuth(person.cliToken)
            contentType(ContentType.Application.Json)
            setBody("""{"agent":"claude-code","model":"$model"}""")
        }

    @Test
    fun aLaunchIsRefusedTheModelsTheGatewayWouldRefuse() = serverTest(modules, overrides) { server ->
        with(server) {
            val ada = person("ada")
            assertEquals(AgentPolicyResponse(allowed = true), verdict(ada, "gpt-5"), "No policy yet: every model")

            val policies = koin.get<GatewaySettingsRepository>()
            policies.putPolicy(ada.orgId, ModelPolicy(allowed = listOf("claude-")))
            val notGpt = "Your organization does not allow the model gpt-5."
            assertEquals(AgentPolicyResponse(false, notGpt), verdict(ada, "gpt-5"))
            val refused = launch(ada, "gpt-5")
            assertEquals(HttpStatusCode.Forbidden, refused.status, refused.bodyAsText())
            assertTrue(notGpt in refused.bodyAsText(), refused.bodyAsText())
            val allowed = launch(ada, "claude-opus-4-1")
            assertEquals(HttpStatusCode.Created, allowed.status, allowed.bodyAsText())
            val launchId = allowed.body<LaunchResponse>().launchId
            assertEquals("claude-opus-4-1", koin.get<LaunchesRepository>().find(ada.orgId, launchId)?.model)

            // The member's own list replaces the organization's, as at a model call.
            val own = mapOf("${ada.memberId}" to listOf("gpt-"))
            policies.putPolicy(ada.orgId, ModelPolicy(allowed = listOf("claude-"), byMember = own))
            assertEquals(AgentPolicyResponse(allowed = true), verdict(ada, "gpt-5"))
            val notClaude = "Your organization does not allow the model claude-opus-4-1."
            assertEquals(AgentPolicyResponse(false, notClaude), verdict(ada, "claude-opus-4-1"))
            assertEquals(HttpStatusCode.Forbidden, launch(ada, "claude-opus-4-1").status)

            // Another organization's policy is its own.
            val bob = person("bob")
            assertNull(verdict(bob, "gpt-5").reason)
            assertEquals(HttpStatusCode.Created, launch(bob, "gpt-5").status)
        }
    }
}
