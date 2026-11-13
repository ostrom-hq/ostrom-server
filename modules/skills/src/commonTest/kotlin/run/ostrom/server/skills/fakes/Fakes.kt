// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.skills.fakes

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.TokenAuthenticator
import org.koin.dsl.module
import kotlin.uuid.Uuid

val ORG_A: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000aa")
val ORG_B: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000bb")
val OWNER: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000a")
val DAN: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000d")
val ADA: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000ad")
val BEN: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000be")
val ZOE: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000ef")

/** The members of the tests' organizations. */
private val MEMBERS = mapOf(ORG_A to setOf(OWNER, DAN, ADA, BEN), ORG_B to setOf(ZOE)).mapValues { (_, ids) ->
    ids.map { it.toString() }.toSet()
}

private fun browser(subject: String) = AuthenticatedPrincipal(
    subject, PrincipalKind.USER, setOf("user:*"), "$subject-session", channel = PrincipalChannel.BROWSER,
)

/**
 * The test identities. Browser sessions: `owner` (owner of A), `dan` (admin of A), `ada` and `ben` (members of A),
 * `zoe` (owner of B), `nobody` (no organization). Ada's tokens: `ada-cli` (her workstation, `skills:read` and
 * `skills:write`), `ada-pat` (a personal token without a skill scope), `ada-app` (the unlocked app, `user:*`), and
 * `ada-key` (her service key, `skills:read`). The owner's keys: `ci` (`skills:publish`, `skills:pin`,
 * `skills:assign`) and `ci-publish` (`skills:publish` alone). `agent` is an agent's token. [audit] keeps the log.
 */
fun testIdentities(audit: RecordingAudit = RecordingAudit()) = module {
    single<TokenAuthenticator> {
        object : TokenAuthenticator {
            override suspend fun authenticate(token: String): AuthenticatedPrincipal? = when (token) {
                "owner", "dan", "ada", "ben", "zoe", "nobody" -> browser(token)
                "ada-cli" -> AuthenticatedPrincipal(
                    "ada", PrincipalKind.USER, setOf("skills:read", "skills:write"), "dev-1", workstation = "Ada's Mac",
                )
                "ben-cli" -> AuthenticatedPrincipal(
                    "ben", PrincipalKind.USER, setOf("skills:read", "skills:write"), "dev-2", workstation = "Ben's PC",
                )
                // A workstation's token read without its id: refused as no workstation.
                "ada-odd" ->
                    AuthenticatedPrincipal("ada", PrincipalKind.USER, setOf("skills:read"), workstation = "Odd")
                "ada-pat" -> AuthenticatedPrincipal("ada", PrincipalKind.USER, setOf("profile:read"), "pat-1")
                "ada-app" -> AuthenticatedPrincipal(
                    "ada", PrincipalKind.USER, setOf("user:*"), "app-1", channel = PrincipalChannel.MOBILE_APP,
                )
                "ada-key" -> AuthenticatedPrincipal("ada-key", PrincipalKind.SERVICE, setOf("skills:read"), "key-3")
                "ci" -> AuthenticatedPrincipal(
                    "ci", PrincipalKind.SERVICE, setOf("skills:publish", "skills:pin", "skills:assign"), "key-1",
                )
                "ci-publish" ->
                    AuthenticatedPrincipal("ci-publish", PrincipalKind.SERVICE, setOf("skills:publish"), "key-2")
                "agent" -> AuthenticatedPrincipal("run-1", PrincipalKind.AGENT, setOf("agent"), "agent-1")
                else -> null
            }
        }
    }
    single<PrincipalOrgResolver> { TestOrgResolver }
    single<MemberDirectory> {
        object : MemberDirectory {
            override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean =
                memberId in MEMBERS[orgId].orEmpty()
        }
    }
    single<OrgAuditTrail> { audit }
}

/** The test identities' organizations: a key acts as its creator, always as a plain member. */
object TestOrgResolver : PrincipalOrgResolver {
    override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
        when (principal.subjectId) {
            "owner" -> OrgPrincipal(ORG_A, OWNER, OrgRole.OWNER)
            "dan" -> OrgPrincipal(ORG_A, DAN, OrgRole.ADMIN)
            "ada", "ada-key" -> OrgPrincipal(ORG_A, ADA, OrgRole.MEMBER)
            "ben" -> OrgPrincipal(ORG_A, BEN, OrgRole.MEMBER)
            "zoe" -> OrgPrincipal(ORG_B, ZOE, OrgRole.OWNER)
            "ci", "ci-publish" -> OrgPrincipal(ORG_A, OWNER, OrgRole.MEMBER)
            else -> null
        }
}

/** The audit log of the tests, in memory. */
class RecordingAudit : OrgAuditTrail {
    val records = mutableListOf<OrgAuditRecord>()

    override suspend fun record(record: OrgAuditRecord) {
        records += record
    }
}
