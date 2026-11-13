// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * A GitHub account, as GitHub itself reports the owner of a token it issued to Ostrom's app.
 *
 * @property id GitHub's stable id of the account.
 * @property login Its GitHub login.
 */
data class GitHubAccount(val id: Long, val login: String)

/**
 * Asks GitHub who a token belongs to, and whether GitHub issued it to Ostrom's own app (audit S2-22): a token minted
 * for another OAuth app, or a revoked one, is unknown there. The answer never comes from the token's own `/user`,
 * which any GitHub token answers.
 */
interface GitHubTokenVerifier {
    /**
     * Checks a token.
     *
     * @param token The GitHub token, as the CLI sent it.
     *
     * @return The account it belongs to, or null when GitHub did not issue it to Ostrom's app.
     *
     * @throws ProviderNotConfiguredException When this instance has no GitHub app.
     * @throws ProviderRejectedException When GitHub cannot be reached or answers something unusable.
     */
    suspend fun verify(token: String): GitHubAccount?
}

/**
 * A launcher login that succeeded.
 *
 * @property user The Ostrom user of the GitHub account.
 * @property token The CLI token issued, with its secret.
 */
data class LauncherLogin(val user: User, val token: IssuedToken)

/** Issues a CLI token ([CLI_SCOPES], [CLI_TOKEN_LIFETIME]) to a user who proved who they are. */
interface IssueCliTokenUseCase {
    /**
     * Issues the token.
     *
     * @param userId The user.
     * @param via What the CLI says it is (machine name), the token's label; blank or null gives a default label.
     *
     * @return The token and its secret, given once. Beyond [MAX_DEVICES_PER_USER] workstations, the oldest is signed
     *   out.
     */
    suspend operator fun invoke(userId: Uuid, via: String?): IssuedToken
}

/**
 * [IssueCliTokenUseCase] on the repository.
 *
 * @param tokens The tokens.
 * @param secrets Makes the secret.
 * @param hasher Hashes it.
 * @param audit The audit log (`token.created`).
 * @param clock The clock.
 */
class IssueCliTokenUseCaseImpl(
    private val tokens: ApiTokensRepository,
    private val secrets: SecretGenerator,
    private val hasher: SecretHasher,
    private val audit: AuditLog,
    private val clock: Clock,
) : IssueCliTokenUseCase {
    override suspend fun invoke(userId: Uuid, via: String?): IssuedToken {
        val now = clock.nowMillis()
        val name = trimmedOrNull(via, MAX_NAME) ?: DEFAULT_NAME
        val expiresAt = now + CLI_TOKEN_LIFETIME
        makeRoomForDevice(tokens, audit, userId, now)
        val issued = issue(secrets, TokenKind.CLI) { id, hint ->
            ApiToken(id, userId, null, name, TokenKind.CLI, CLI_SCOPES, hint, null, now, expiresAt, null, null)
        }
        tokens.create(issued.token, hasher.hash(issued.secret))
        val details = mapOf("kind" to "cli", "via" to "launcher")
        audit.record(AuditEntry(null, userId, "token.created", "token", issued.token.id.toString(), details))
        return issued
    }

    private companion object {
        const val MAX_NAME = 100
        const val DEFAULT_NAME = "ostrom launcher"
    }
}

/**
 * `POST /api/v1/auth/launcher/login`: exchanges a GitHub token the CLI obtained from Ostrom's GitHub app for a CLI
 * token of the Ostrom user who signs in with that GitHub account. GitHub is asked first (S2-22); the GitHub token is
 * neither kept nor logged. No account is created here: the person signs up in the console first.
 */
interface LauncherLoginUseCase {
    /**
     * Logs the launcher in.
     *
     * @param githubToken The GitHub token.
     * @param via What the CLI says it is (machine name), the token's label.
     * @param source Who is asking (the client address), for the rate limit.
     *
     * @return The user and their new CLI token.
     *
     * @throws GitHubTokenRejectedException When the token is empty, oversized, or GitHub did not issue it to Ostrom.
     * @throws RateLimitedException When the address tries too often.
     * @throws LauncherAccountNotFoundException When no Ostrom user signs in with that GitHub account.
     * @throws ProviderNotConfiguredException When this instance has no GitHub app.
     * @throws ProviderRejectedException When GitHub cannot be reached.
     */
    suspend operator fun invoke(githubToken: String, via: String?, source: String): LauncherLogin
}

/**
 * [LauncherLoginUseCase].
 *
 * @param verifier Asks GitHub about the token.
 * @param users Finds the user of the GitHub account.
 * @param limiter Limits attempts per address.
 * @param issueCliToken Issues the CLI token.
 */
class LauncherLoginUseCaseImpl(
    private val verifier: GitHubTokenVerifier,
    private val users: UsersRepository,
    private val limiter: RateLimiter,
    private val issueCliToken: IssueCliTokenUseCase,
) : LauncherLoginUseCase {
    override suspend fun invoke(githubToken: String, via: String?, source: String): LauncherLogin {
        if (githubToken.isBlank() || githubToken.length > MAX_GITHUB_TOKEN) throw GitHubTokenRejectedException()
        val key = "launcher-login:${rateLimitSource(source)}"
        if (!limiter.tryAcquire(key, LIMIT, LIMIT_WINDOW_MINUTES.minutes)) {
            throw RateLimitedException()
        }
        val account = verifier.verify(githubToken) ?: throw GitHubTokenRejectedException()
        val user = users.findByIdentity(IdentityProvider.GITHUB.key, account.id.toString())
            ?: throw LauncherAccountNotFoundException()
        return LauncherLogin(user, issueCliToken(user.id, via))
    }

    private companion object {
        /** GitHub tokens are far shorter; a longer value is refused before GitHub is asked. */
        const val MAX_GITHUB_TOKEN = 255
        const val LIMIT = 10
        const val LIMIT_WINDOW_MINUTES = 10
    }
}

/** GitHub did not issue this token to Ostrom's app (or it is empty, oversized, revoked). */
class GitHubTokenRejectedException :
    OstromException(ErrorKind.UNAUTHORIZED, "GitHub did not issue this token to Ostrom. Run `ostrom login` again.")

/** No Ostrom user signs in with the GitHub account of the token. */
class LauncherAccountNotFoundException : OstromException(
    ErrorKind.NOT_FOUND,
    "No Ostrom account signs in with this GitHub account. Sign up in the console first (on an invite-only instance, " +
        "from your invitation), then run `ostrom login` again.",
)
