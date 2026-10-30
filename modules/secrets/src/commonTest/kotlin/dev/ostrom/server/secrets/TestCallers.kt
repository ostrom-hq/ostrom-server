// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.secrets

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalChannel.BROWSER
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.domain.MemberRole
import dev.ostrom.server.secrets.domain.RelayScopes
import dev.ostrom.server.secrets.domain.SecretCipher
import dev.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.uuid.Uuid

/** Two orgs and their members, each with a bearer token equal to its name, for route tests. */
object TestCallers {
    val orgA: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    val orgB: Uuid = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    val adminA = Caller(orgA, Uuid.parse("0000000a-0000-0000-0000-0000000000a1"), MemberRole.ADMIN)
    val memberA = Caller(orgA, Uuid.parse("0000000a-0000-0000-0000-0000000000b1"), MemberRole.MEMBER)
    val adminB = Caller(orgB, Uuid.parse("0000000b-0000-0000-0000-0000000000a1"), MemberRole.OWNER)
    private val byToken = mapOf(
        "admin-a" to adminA,
        "member-a" to memberA,
        "admin-b" to adminB,
        "agent-a" to memberA,
        "cli-a" to memberA,
        "device-a" to memberA,
        "cli-write-a" to memberA,
    )

    private val browserScopes = setOf(RelayScopes.ALL_USER)

    /**
     * What each token is: browser sessions, plus an agent, a CLI token that may only read, a personal token holding
     * every secrets scope, and a device.
     */
    private val principals = mapOf(
        "agent-a" to AuthenticatedPrincipal("agent-a", PrincipalKind.SERVICE, setOf(RelayScopes.AGENT_SESSION)),
        "cli-a" to AuthenticatedPrincipal("cli-a", PrincipalKind.USER, setOf("profile:read", RelayScopes.SECRETS_READ)),
        "device-a" to AuthenticatedPrincipal("device-a", PrincipalKind.DEVICE, setOf(RelayScopes.ALL_USER)),
        "cli-write-a" to AuthenticatedPrincipal(
            "cli-write-a",
            PrincipalKind.USER,
            setOf(RelayScopes.SECRETS_READ, RelayScopes.SECRETS_WRITE),
        ),
    )

    /**
     * Koin overrides (`stranger` is a valid token of nobody's org): the fake token authenticator and caller resolver,
     * and a fixed secrets key.
     */
    val overrides: Module = module {
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) = principals[token]
                    ?: (byToken[token] ?: token.takeIf { it == "stranger" })
                        ?.let { AuthenticatedPrincipal(token, PrincipalKind.USER, browserScopes, channel = BROWSER) }
            }
        }
        single<CallerResolver> {
            object : CallerResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): Caller? =
                    byToken[principal.subjectId]?.takeIf { orgId == null || it.orgId == orgId }
            }
        }
    }
}
