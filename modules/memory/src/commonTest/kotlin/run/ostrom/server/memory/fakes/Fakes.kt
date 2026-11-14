// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.fakes

import run.ostrom.server.kernel.auth.ALL_USER_SCOPES
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.memory.domain.MemoryCaller
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.MemberRole
import kotlin.uuid.Uuid

val ORG_A: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000a")
val ORG_B: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000b")
const val ADA = "00000000-0000-0000-0000-0000000000ad"
const val BEN = "00000000-0000-0000-0000-0000000000be"
const val ROOT = "00000000-0000-0000-0000-0000000000a0"
const val EVE = "00000000-0000-0000-0000-0000000000ee"

/**
 * The credentials of the tests: Ada and Ben are members of organization A, Root its administrator, Eve a member of B.
 * `ada` is Ada's browser, `adacli` her CLI sign-in, `adaapp` her phone; `adakey` a personal token of hers with
 * `memory:use`, `adabare` one without; `agent` the token of a launch of Ada's with `memory:use`, `oldagent` one issued
 * before the memory; `service` a key of Ada's with `memory:use`; `nobody` belongs to no organization.
 */
object Tokens : TokenAuthenticator {
    override suspend fun authenticate(token: String): AuthenticatedPrincipal? = when (token) {
        "ada", "ben", "root", "eve", "nobody" -> person(token, PrincipalChannel.BROWSER)
        "adacli" -> person("ada", PrincipalChannel.API_TOKEN, signedIn = true)
        "rootcli" -> person("root", PrincipalChannel.API_TOKEN, signedIn = true)
        "adaapp" -> person("ada", PrincipalChannel.MOBILE_APP)
        "adakey" -> AuthenticatedPrincipal("ada", PrincipalKind.USER, setOf("memory:use"), "tok-ada")
        "rootkey" -> AuthenticatedPrincipal("root", PrincipalKind.USER, setOf("memory:use"), "tok-root")
        "adabare" -> AuthenticatedPrincipal("ada", PrincipalKind.USER, setOf("artifacts:read"), "tok-bare")
        "agent" -> AuthenticatedPrincipal(LAUNCH, PrincipalKind.AGENT, setOf("agent:session", "memory:use"), "osa-1")
        "oldagent" -> AuthenticatedPrincipal(LAUNCH, PrincipalKind.AGENT, setOf("agent:session"), "osa-0")
        "service" -> AuthenticatedPrincipal("ada", PrincipalKind.SERVICE, setOf("memory:use"), "key-1")
        else -> null
    }

    private fun person(login: String, channel: PrincipalChannel, signedIn: Boolean = false) =
        AuthenticatedPrincipal(
            login,
            PrincipalKind.USER,
            setOf(ALL_USER_SCOPES),
            channel = channel,
            signedIn = signedIn,
        )

    const val LAUNCH: String = "lch_1"
}

/** The member each credential acts for; an agent token is Ada's, as a plain member. */
object Callers : CallerResolver {
    override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): Caller? =
        when (principal.subjectId) {
            "ada", "service", Tokens.LAUNCH -> Caller(ORG_A, Uuid.parse(ADA), MemberRole.MEMBER)
            "ben" -> Caller(ORG_A, Uuid.parse(BEN), MemberRole.MEMBER)
            "root" -> Caller(ORG_A, Uuid.parse(ROOT), MemberRole.ADMIN)
            "eve" -> Caller(ORG_B, Uuid.parse(EVE), MemberRole.OWNER)
            else -> null
        }
}

/** A caller of the domain, a person by default. */
fun caller(
    member: String = ADA,
    org: Uuid = ORG_A,
    admin: Boolean = false,
    person: Boolean = true,
    agent: Boolean = false,
): MemoryCaller = MemoryCaller(
    org,
    member,
    admin,
    person,
    agent,
    AuthenticatedPrincipal(member, if (agent) PrincipalKind.AGENT else PrincipalKind.USER, setOf("memory:use")),
)
