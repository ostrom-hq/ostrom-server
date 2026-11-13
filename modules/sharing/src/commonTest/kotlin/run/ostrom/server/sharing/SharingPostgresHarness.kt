// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.RelayScopes
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.sharing.presentation.AccountLimitsDto
import run.ostrom.server.sharing.presentation.ShareAccountRequest
import run.ostrom.server.sharing.presentation.UpdateAccountRequest
import run.ostrom.server.testing.TestServer
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/** The shared set-up of the sharing tests on real PostgreSQL and real HTTP: callers by token, and a share. */
abstract class SharingPostgresHarness {
    protected val byToken = mapOf(
        "admin-a" to Sharing.adminA,
        "member-a" to Sharing.memberA,
        "other-a" to Sharing.otherA,
        "admin-b" to Sharing.adminB,
        "agent-a" to Sharing.memberA,
        "device-a" to Sharing.memberA,
        "reader-a" to Sharing.memberA,
    )

    /** Browser sessions hold every right of the user; the others hold what they list. */
    protected val principals = mapOf(
        "agent-a" to AuthenticatedPrincipal("agent-a", PrincipalKind.SERVICE, setOf(RelayScopes.AGENT_SESSION)),
        "device-a" to AuthenticatedPrincipal("device-a", PrincipalKind.DEVICE, setOf(RelayScopes.ALL_USER)),
        "reader-a" to AuthenticatedPrincipal("reader-a", PrincipalKind.USER, setOf(RelayScopes.ACCOUNTS_READ)),
    )

    protected val authenticator = module {
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) = principals[token]
                    ?: byToken[token]?.let {
                        val scopes = setOf(RelayScopes.ALL_USER)
                        AuthenticatedPrincipal(
                            token, PrincipalKind.USER, scopes, channel = PrincipalChannel.BROWSER,
                            authenticatedAt = Clock.System.now(),
                        )
                    }
            }
        }
    }

    protected val overrides: Module = module {
        includes(authenticator)
        single<CallerResolver> {
            object : CallerResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): Caller? =
                    byToken[principal.subjectId]?.takeIf { orgId == null || it.orgId == orgId }
            }
        }
    }

    protected val base = "/api/v1/accounts"
    protected val modules = listOf(SecretsModule, SharingModule)

    protected suspend fun TestServer.shareAs(
        token: String,
        id: String = "acct-uuid-1",
        label: String = "Lyes Max 20x",
        expiresAt: String = (Clock.System.now() + 8.hours).toString(),
    ): HttpResponse = client.post(base) {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(
            ShareAccountRequest(
                provider = "claude",
                label = label,
                email = "lyes@example.com",
                providerAccountId = id,
                accessToken = "sk-ant-oat01-secret-access",
                refreshToken = "sk-ant-ort01-secret-refresh",
                expiresAt = expiresAt,
                limits = AccountLimitsDto(maxConcurrent = 4),
            ),
        )
    }

    protected suspend fun TestServer.patchAs(token: String, path: String, request: UpdateAccountRequest) =
        client.patch(path) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
}
