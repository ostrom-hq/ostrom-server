// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.app.isolation

import dev.ostrom.server.identity.domain.ADMIN_TOKEN_SCOPES
import dev.ostrom.server.identity.domain.ApiToken
import dev.ostrom.server.identity.domain.ApiTokensRepository
import dev.ostrom.server.identity.domain.CLI_SCOPES
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.MOBILE_SCOPES
import dev.ostrom.server.identity.domain.MobileTokensRepository
import dev.ostrom.server.identity.domain.PERSONAL_TOKEN_SCOPES
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.SERVICE_KEY_SCOPES
import dev.ostrom.server.identity.domain.SecretHasher
import dev.ostrom.server.identity.domain.StartWebSessionUseCase
import dev.ostrom.server.identity.domain.StartedSession
import dev.ostrom.server.identity.domain.TokenKind
import dev.ostrom.server.identity.domain.User
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.kernel.auth.CSRF_COOKIE
import dev.ostrom.server.kernel.auth.CSRF_HEADER
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.SESSION_COOKIE
import dev.ostrom.server.orgs.domain.Invitation
import dev.ostrom.server.orgs.domain.InvitationsRepository
import dev.ostrom.server.orgs.domain.Member
import dev.ostrom.server.orgs.domain.Org
import dev.ostrom.server.orgs.domain.OrgsRepository
import dev.ostrom.server.testing.TestServer
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/** The console's origin in tests: the default `OSTROM_CONSOLE_URL`. */
internal const val CONSOLE_ORIGIN = "http://localhost:5173"

/** A person of an organization: who they are, their browser session and their membership. */
internal class Person(val user: User, val session: StartedSession, val memberId: Uuid)

/**
 * One organization of the matrix and the people and credentials of it: an owner, a simple member, and a token of
 * every kind. Every name it holds carries [marker], unique to the run, so that B's data is recognizable anywhere.
 *
 * @property values What [seed][seed] made in it, by kind, for the paths and bodies of the matrix.
 * @property problems What could not be seeded, reported by the test.
 */
internal class Party(
    val marker: String,
    val org: Org,
    val owner: Person,
    val member: Person,
    val tokens: Map<TokenKind, String>,
    tokenIds: Map<TokenKind, Uuid>,
) {
    val values = mutableMapOf(
        Seed.ORG_ID to "${org.id}",
        Seed.ORG_SLUG to org.slug,
        Seed.MEMBER_LOGIN to member.user.login,
        Seed.MEMBER_ID to "${member.memberId}",
        Seed.AUTH_SESSION to "${owner.session.session.id}",
        Seed.DEVICE to "${tokenIds.getValue(TokenKind.CLI)}",
    )
    val problems = mutableListOf<String>()

    /** The agent token of the organization's launch, once [seed] has launched it. */
    var agentToken: String? = null

    /** The owner's browser: the session cookie, the CSRF header repeating it, the console's origin. */
    val browser = Credential("browser owner") { session(owner.session) }

    /** The simple member's browser. */
    val memberBrowser = Credential("browser member") { session(member.session) }

    /** Every way to call the server as someone of this organization, and as nobody. */
    val credentials: List<Credential>
        get() = listOf(
            Credential("anonymous") {},
            browser,
            memberBrowser,
        ) + tokens.map { (kind, secret) -> Credential("${kind.name.lowercase()} token") { bearerAuth(secret) } } +
            listOfNotNull(agentToken?.let { secret -> Credential("agent token") { bearerAuth(secret) } })

    /** What only this organization's data contains: its marker, its ids, its distinctive figures. */
    fun canaries(): Set<String> =
        setOf(marker, "${org.id}", SPENT_USD, "$SPENT_MICROS") + values.values.filter { it.length >= CANARY_LENGTH }

    companion object {
        /** What B spends, in micro-dollars: A spends nothing, so any total of A that counts B shows this figure. */
        const val SPENT_MICROS = 7_654_321L

        /** [SPENT_MICROS] as the reports write it. */
        const val SPENT_USD = "7.654321"

        // Shorter values (a version number) could appear in any answer.
        private const val CANARY_LENGTH = 8
    }
}

/** A browser call: the session cookie, the CSRF header repeating its cookie, from the console's origin. */
internal fun HttpRequestBuilder.session(started: StartedSession) {
    header(HttpHeaders.Cookie, "$SESSION_COOKIE=${started.secret}; $CSRF_COOKIE=${started.csrfToken}")
    header(CSRF_HEADER, started.csrfToken)
    header(HttpHeaders.Origin, CONSOLE_ORIGIN)
}

/**
 * Makes an organization: its owner and a simple member, signed in, and one token of every kind bound to it, made as the
 * device flow, the console, `ostrom login --admin` and the phone app make them (the rows, not the flows: those are
 * tested in identity). The agent token comes from a launch, in [seed].
 *
 * @param tag A letter for the logs: `a` or `b`.
 */
internal suspend fun TestServer.party(tag: String): Party {
    val marker = "zq$tag" + Uuid.random().toHexString().take(10)
    val now = Clock.System.now()
    val owner = signUp("${marker}o")
    val member = signUp("${marker}m")
    val org = Org(Uuid.random(), "$marker-team", "$marker team", null, null, now)
    val ownership = Member(Uuid.random(), org.id, owner.id, OrgRole.OWNER, now)
    koin.get<OrgsRepository>().create(org, ownership) { false }
    val invitation =
        Invitation(Uuid.random(), org.id, null, OrgRole.MEMBER, owner.id, now, now + 1.hours, null, 0, null)
    val invitations = koin.get<InvitationsRepository>()
    invitations.create(invitation, "hash-" + Uuid.random().toHexString())
    val membership = Member(Uuid.random(), org.id, member.id, OrgRole.MEMBER, now)
    check(invitations.accept(invitation, membership, now)) { "$marker: the member did not join" }
    val sessions = koin.get<StartWebSessionUseCase>()
    val scopes = mapOf(
        TokenKind.CLI to CLI_SCOPES,
        TokenKind.PERSONAL to PERSONAL_TOKEN_SCOPES.map { it.scope }.toSet(),
        TokenKind.ADMIN to ADMIN_TOKEN_SCOPES,
        TokenKind.MOBILE to MOBILE_SCOPES,
        TokenKind.SERVICE to SERVICE_KEY_SCOPES,
    )
    val tokens = scopes.mapValues { (kind, granted) -> token(owner, org, kind, granted) }
    val secrets = tokens.mapValues { it.value.second }
    val people = Person(owner, sessions(owner.id), ownership.id) to Person(member, sessions(member.id), membership.id)
    return Party(marker, org, people.first, people.second, secrets, tokens.mapValues { it.value.first })
}

private suspend fun TestServer.signUp(login: String): User = koin.get<ResolveUserUseCase>()(
    VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
)

// A token row of [kind] bound to [org], as its flow stores it; returns its id and its secret.
internal suspend fun TestServer.token(owner: User, org: Org, kind: TokenKind, scopes: Set<String>): Pair<Uuid, String> {
    val now = Clock.System.now()
    val secret = "${kind.prefix}_" + Uuid.random().toHexString()
    val row = ApiToken(
        Uuid.random(), owner.id, org.id, kind.name.lowercase(), kind, scopes, secret.takeLast(4), null, now,
        now + 8.hours, null, null, authenticatedAt = now,
    )
    val hasher = koin.get<SecretHasher>()
    // The phone app's token is stored with the hash of its unlock key, as a mobile sign-in stores it.
    if (kind == TokenKind.MOBILE) {
        koin.get<MobileTokensRepository>().create(row, hasher.hash(secret), hasher.hash("unlock-$secret"))
    } else {
        koin.get<ApiTokensRepository>().create(row, hasher.hash(secret))
    }
    return row.id to secret
}
