// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.fakes

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.testing.TestInstanceSettings
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.MapConfigSource
import org.koin.dsl.module
import kotlin.uuid.Uuid

val ALICE: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000a11c")
val BOB: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000b0b")
val CAROL: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000ca01")
val DANA: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000da0a")

/**
 * The test identities: the tokens `alice` and `bob` (members of org A), `dana` (admin of org A), `carol` (owner of
 * org B) and `nobody` (no org) are browser sessions with every user scope; `reader` is a service token of alice's with
 * `cloud:read` only, `dana-cli` a personal API token of dana's with `cloud:write`. Cloud sessions are open to org A
 * only. A request whose `Ostrom-Org` header names an organization the caller is not a member of resolves to no one.
 */
val testIdentities = testIdentitiesWith(emptyMap())

/**
 * [testIdentities] with another configuration of the runners module; cloud sessions stay open to org A only.
 *
 * @param config The configuration.
 *
 * @return The module.
 */
fun testIdentitiesWith(config: Map<String, String>) = module {
    single<TokenAuthenticator> {
        object : TokenAuthenticator {
            override suspend fun authenticate(token: String): AuthenticatedPrincipal? = when (token) {
                "alice", "bob", "carol", "dana", "nobody" -> {
                    val scopes = setOf("user:*")
                    AuthenticatedPrincipal(token, PrincipalKind.USER, scopes, channel = PrincipalChannel.BROWSER)
                }
                "reader" -> AuthenticatedPrincipal("alice", PrincipalKind.SERVICE, setOf("cloud:read"))
                "dana-cli" -> AuthenticatedPrincipal("dana", PrincipalKind.USER, setOf("cloud:write"))
                else -> null
            }
        }
    }
    single<PrincipalOrgResolver> { TestOrgResolver }
    single<ConfigSource> { MapConfigSource(config) }
    single<InstanceSettings> { TestInstanceSettings(ORG_A_ALLOWED) }
}

/** The console's cloud access opening cloud sessions to org A only. */
val ORG_A_ALLOWED: Map<String, Map<String, String>> = mapOf("cloud-access" to mapOf("allowedOrgs" to ORG_A.toString()))

/** Resolves the test identities' organizations (the credential's own: the cloud routes name none). */
object TestOrgResolver : PrincipalOrgResolver {
    override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? {
        val member = when (principal.subjectId) {
            "alice" -> OrgPrincipal(ORG_A, ALICE, OrgRole.MEMBER)
            "bob" -> OrgPrincipal(ORG_A, BOB, OrgRole.MEMBER)
            "dana" -> OrgPrincipal(ORG_A, DANA, OrgRole.ADMIN)
            "carol" -> OrgPrincipal(ORG_B, CAROL, OrgRole.OWNER)
            else -> null
        }
        val named = principal.namedOrg
        return member?.takeIf { named == null || named == it.orgId.toString() }
    }
}
