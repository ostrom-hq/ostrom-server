// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.secrets

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalChannel.BROWSER
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.MemberRole
import run.ostrom.server.secrets.domain.RelayScopes
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.time.Clock
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Two orgs and their members, each with a bearer token equal to its name, for route tests. */
object TestCallers {
    val orgA: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    val orgB: Uuid = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    val adminA = Caller(orgA, Uuid.parse("0000000a-0000-0000-0000-0000000000a1"), MemberRole.ADMIN)
    val memberA = Caller(orgA, Uuid.parse("0000000a-0000-0000-0000-0000000000b1"), MemberRole.MEMBER)
    val adminB = Caller(orgB, Uuid.parse("0000000b-0000-0000-0000-0000000000a1"), MemberRole.OWNER)
    val member2A = Caller(orgA, Uuid.parse("0000000a-0000-0000-0000-0000000000b2"), MemberRole.MEMBER)
    private val byToken = mapOf(
        "admin-a" to adminA,
        "member-a" to memberA,
        "member2-a" to member2A,
        "service-a" to memberA,
        "cli-env-a" to memberA,
        "admin-b" to adminB,
        "agent-a" to memberA,
        "cli-a" to memberA,
        "device-a" to memberA,
        "cli-write-a" to memberA,
        // A browser session of the admin signed in more than five minutes ago (web audit n°2 M3).
        "stale-admin-a" to adminA,
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
        "service-a" to AuthenticatedPrincipal(
            "service-a",
            PrincipalKind.SERVICE,
            setOf(RelayScopes.ENVIRONMENT_READ, RelayScopes.SECRETS_READ),
        ),
        "cli-env-a" to AuthenticatedPrincipal("cli-env-a", PrincipalKind.USER, setOf(RelayScopes.ENVIRONMENT_READ)),
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
                        ?.let {
                            AuthenticatedPrincipal(
                                token, PrincipalKind.USER, browserScopes, channel = BROWSER,
                                authenticatedAt = signedIn(token),
                            )
                        }
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

// When the fake browser session signed in: now, or more than five minutes ago for a `stale-` token.
private fun signedIn(token: String): Instant =
    Clock.System.now() - if (token.startsWith("stale-")) 10.minutes else ZERO
