// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.fakes

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalChannel
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.MapConfigSource
import org.koin.dsl.module
import kotlin.uuid.Uuid

val ALICE: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000a11c")
val BOB: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000b0b")
val CAROL: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000ca01")

/**
 * The test identities: the tokens `alice` and `bob` (members of org A), `carol` (org B) and `nobody` (no org) are
 * browser sessions with every user scope; `reader` is a service token of alice's with `cloud:read` only. Cloud
 * sessions are open to org A only.
 */
val testIdentities = module {
    single<TokenAuthenticator> {
        object : TokenAuthenticator {
            override suspend fun authenticate(token: String): AuthenticatedPrincipal? = when (token) {
                "alice", "bob", "carol", "nobody" -> {
                    val scopes = setOf("user:*")
                    AuthenticatedPrincipal(token, PrincipalKind.USER, scopes, channel = PrincipalChannel.BROWSER)
                }
                "reader" -> AuthenticatedPrincipal("alice", PrincipalKind.SERVICE, setOf("cloud:read"))
                else -> null
            }
        }
    }
    single<PrincipalOrgResolver> { TestOrgResolver }
    single<ConfigSource> { MapConfigSource(mapOf("OSTROM_RUNNERS_ALLOWED_ORGS" to ORG_A.toString())) }
}

/** Resolves the test identities' organizations (the credential's own: the cloud routes name none). */
object TestOrgResolver : PrincipalOrgResolver {
    override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
        when (principal.subjectId) {
            "alice" -> OrgPrincipal(ORG_A, ALICE, OrgRole.MEMBER)
            "bob" -> OrgPrincipal(ORG_A, BOB, OrgRole.MEMBER)
            "carol" -> OrgPrincipal(ORG_B, CAROL, OrgRole.OWNER)
            else -> null
        }
}
