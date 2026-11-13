// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.identity.domain

import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The `error` of a mobile callback when the person refused at the provider. */
const val MOBILE_ACCESS_DENIED = "access_denied"

/** The `error` of a mobile callback when the provider refused the code. */
const val MOBILE_REFUSED = "refused"

/** The `error` of a mobile callback when the provider names the address of another account (S2-07). */
const val MOBILE_LINK_REQUIRED = "link_required"

/**
 * The `error` of a mobile callback when an invite-only instance has no account for this person: they open their
 * invitation in a browser first, which creates it.
 */
const val MOBILE_SIGNUP_CLOSED = "signup_closed"

/** The app's token kind, to list a person's phones. */
private val MOBILE_KIND = setOf(TokenKind.MOBILE)

/** The audit details of an app token. */
private val MOBILE_DETAILS = mapOf("kind" to "mobile")

/** Completes a sign-in the phone app started, when the provider sends its browser back. */
interface CompleteMobileSignInUseCase {
    /**
     * Checks the state against the browser's cookie, takes the attempt (once), exchanges the provider's code with
     * PKCE, finds or creates the account, then issues the app's one-time code. No browser session is opened.
     *
     * @param provider The provider key in the callback URL.
     * @param state The state the provider returned.
     * @param browserState The state in the browser's mobile cookie, set at the start.
     * @param code The provider's authorization code.
     * @param providerError The provider's `error`, when the person refused.
     *
     * @return The app's deep link with the code, or with an error once the attempt is known.
     *
     * @throws ProviderNotConfiguredException When the provider is unknown or not configured.
     * @throws InvalidSignInStateException When the state is missing, does not match the cookie, is unknown, used or
     *   expired, belongs to another provider, or the attempt was not started by the app.
     */
    suspend operator fun invoke(
        provider: String,
        state: String?,
        browserState: String?,
        code: String?,
        providerError: String?,
    ): MobileCallback
}

/**
 * [CompleteMobileSignInUseCase].
 *
 * @param methods The configured providers.
 * @param attempts Where attempts are kept.
 * @param services Makes and hashes the code; the clock and the callback URLs.
 * @param resolveUser Finds, merges or creates the account.
 * @param codes Where the one-time codes are kept.
 */
class CompleteMobileSignInUseCaseImpl(
    private val methods: SignInMethods,
    private val attempts: OAuthAttemptsRepository,
    private val services: SignInServices,
    private val resolveUser: ResolveUserUseCase,
    private val codes: MobileCodesRepository,
) : CompleteMobileSignInUseCase {
    override suspend fun invoke(
        provider: String,
        state: String?,
        browserState: String?,
        code: String?,
        providerError: String?,
    ): MobileCallback {
        val (oauth, attempt) = takeAttempt(methods, attempts, services, TakenState(provider, state, browserState))
        val mobile = attempt.mobile ?: throw InvalidSignInStateException()
        val callback = MobileCallback(mobile.redirectUri, mobile.state)
        val usable = code?.takeIf { it.isNotEmpty() }
        return when {
            providerError != null -> callback.copy(error = MOBILE_ACCESS_DENIED)
            usable == null -> callback.copy(error = MOBILE_REFUSED)
            else -> signIn(oauth, attempt, mobile, usable, callback)
        }
    }

    private suspend fun signIn(
        oauth: OAuthProvider,
        attempt: OAuthAttempt,
        mobile: MobileClient,
        code: String,
        callback: MobileCallback,
    ): MobileCallback = try {
        val identity = oauth.identity(code, attempt.codeVerifier, attempt.nonce, services.callbackUrl(oauth.provider))
        val user = resolveUser(identity)
        callback.copy(code = issueCode(user.id, mobile), login = user.login)
    } catch (_: ProviderRejectedException) {
        callback.copy(error = MOBILE_REFUSED)
    } catch (_: AccountLinkRequiredException) {
        callback.copy(error = MOBILE_LINK_REQUIRED)
    } catch (_: SignUpClosedException) {
        callback.copy(error = MOBILE_SIGNUP_CLOSED)
    }

    private suspend fun issueCode(userId: Uuid, mobile: MobileClient): String {
        val secret = services.generator.secret()
        val now = services.clock.nowMillis()
        val expiresAt = now + MOBILE_CODE_LIFETIME
        val code = MobileCode(Uuid.random(), userId, mobile.codeChallenge, mobile.redirectUri, now, expiresAt)
        codes.create(code, services.hasher.hash(secret))
        return secret
    }
}

/** Exchanges the one-time code of a mobile sign-in for the app's token (`POST /api/v1/auth/mobile/token`). */
interface ExchangeMobileCodeUseCase {
    /**
     * Takes the code (once, even when what follows fails), checks the redirect URI and the PKCE verifier, then issues
     * the app's token and its unlock key; beyond [MAX_PHONES_PER_USER] live app tokens, the oldest are revoked.
     *
     * @param code The one-time code from the deep link.
     * @param codeVerifier The PKCE verifier only the app knows.
     * @param redirectUri The deep link the code was sent to.
     * @param source Who is asking (the client address), for the rate limit.
     *
     * @return The token, its unlock key and the user.
     *
     * @throws RateLimitedException When the address exchanges too many codes.
     * @throws InvalidMobileCodeException When the code is unknown, used or expired, or the verifier or the redirect
     *   URI does not match.
     */
    suspend operator fun invoke(
        code: String,
        codeVerifier: String,
        redirectUri: String,
        source: String,
    ): IssuedMobileToken
}

/**
 * [ExchangeMobileCodeUseCase].
 *
 * @param codes The one-time codes.
 * @param mobileTokens Stores the app's token with its unlock key.
 * @param tokens Lists and revokes the user's app tokens, for the cap of [MAX_PHONES_PER_USER].
 * @param users Reads the user.
 * @param services Makes and hashes secrets, computes the PKCE challenge, limits the rate; the clock.
 * @param audit The audit log.
 */
class ExchangeMobileCodeUseCaseImpl(
    private val codes: MobileCodesRepository,
    private val mobileTokens: MobileTokensRepository,
    private val tokens: ApiTokensRepository,
    private val users: UsersRepository,
    private val services: SignInServices,
    private val audit: AuditLog,
) : ExchangeMobileCodeUseCase {
    override suspend fun invoke(
        code: String,
        codeVerifier: String,
        redirectUri: String,
        source: String,
    ): IssuedMobileToken {
        if (!services.limiter.tryAcquire("mobile-token:$source", EXCHANGE_LIMIT, MOBILE_LIMIT_WINDOW)) {
            throw RateLimitedException()
        }
        val now = services.clock.nowMillis()
        // The code is spent before anything is checked: a wrong verifier burns it (RFC 6749 § 4.1.2).
        val taken = code.takeIf { it.length in 1..MAX_MOBILE_SECRET_LENGTH }
            ?.let { codes.consume(services.hasher.hash(it), now) }
        val user = taken?.takeIf { it.matches(codeVerifier, redirectUri) }?.let { users.get(it.userId) }
            ?: throw InvalidMobileCodeException()
        return issueToken(user, now)
    }

    private suspend fun MobileCode.matches(verifier: String, uri: String): Boolean = uri == redirectUri &&
        isPkceVerifier(verifier) && constantTimeEquals(services.hasher.pkceChallenge(verifier), codeChallenge)

    private suspend fun issueToken(user: User, now: Instant): IssuedMobileToken {
        // listLive is newest first: what is left beyond the newest MAX_PHONES_PER_USER - 1 makes room for this one.
        tokens.listLive(user.id, MOBILE_KIND, now).drop(MAX_PHONES_PER_USER - 1).forEach {
            tokens.revoke(user.id, it.id, now)
            audit.record(AuditEntry(null, user.id, "token.revoked", "token", it.id.toString(), MOBILE_DETAILS))
        }
        val expiresAt = now + MOBILE_TOKEN_LIFETIME
        val issued = issue(services.generator, TokenKind.MOBILE) { id, hint ->
            val name = MOBILE_TOKEN_NAME
            ApiToken(id, user.id, null, name, TokenKind.MOBILE, MOBILE_SCOPES, hint, null, now, expiresAt, null, null)
        }
        val unlockKey = services.generator.secret()
        mobileTokens.create(issued.token, services.hasher.hash(issued.secret), services.hasher.hash(unlockKey))
        audit.record(AuditEntry(null, user.id, "token.created", "token", issued.token.id.toString(), MOBILE_DETAILS))
        return IssuedMobileToken(issued, unlockKey, user, expiresAt)
    }

    private companion object {
        const val EXCHANGE_LIMIT = 30
        const val MOBILE_TOKEN_NAME = "Ostrom app"
    }
}

/** Records the phone's biometric unlock (`POST /api/v1/auth/mobile/unlock`), the proof a mobile yes needs (S2-54). */
interface UnlockMobileAppUseCase {
    /**
     * Checks the unlock key against the app's token and records the unlock.
     *
     * @param userId The token's owner.
     * @param tokenId The app's token that calls.
     * @param unlockKey The key the app read from behind the phone's biometrics.
     * @param source Who is asking (the client address): with the token, the rate limit, so a copy of the token used
     *   elsewhere cannot exhaust the phone's unlocks.
     * @param requestId The approval request the unlock is for: a yes from the app to it is taken once, within a
     *   minute (Q-APP-STEPUP-RISK). Null binds the unlock to no request.
     *
     * @return When the unlock was recorded: a no is accepted for five minutes after it, a yes only as above.
     *
     * @throws RateLimitedException When the token unlocks too often.
     * @throws MobileUnlockRefusedException When the key is wrong or the token is not a live app token.
     */
    suspend operator fun invoke(
        userId: Uuid,
        tokenId: Uuid,
        unlockKey: String,
        source: String,
        requestId: String? = null,
    ): Instant
}

/**
 * [UnlockMobileAppUseCase].
 *
 * @param mobileTokens The app's tokens.
 * @param hasher Hashes the key.
 * @param limiter Limits the unlocks per token and address.
 * @param audit Records each unlock: the proof behind a yes from the phone.
 * @param clock The clock.
 */
class UnlockMobileAppUseCaseImpl(
    private val mobileTokens: MobileTokensRepository,
    private val hasher: SecretHasher,
    private val limiter: RateLimiter,
    private val audit: AuditLog,
    private val clock: Clock,
) : UnlockMobileAppUseCase {
    override suspend fun invoke(
        userId: Uuid,
        tokenId: Uuid,
        unlockKey: String,
        source: String,
        requestId: String?,
    ): Instant {
        if (!limiter.tryAcquire("mobile-unlock:$tokenId:$source", UNLOCK_LIMIT, MOBILE_LIMIT_WINDOW)) {
            throw RateLimitedException()
        }
        val now = clock.nowMillis()
        val wellFormed = unlockKey.length in 1..MAX_MOBILE_SECRET_LENGTH
        // An id longer than any request's names none: it is cut, and still binds the unlock to nothing a yes can use.
        val request = requestId?.take(MAX_REQUEST_ID_LENGTH)
        if (!wellFormed || !mobileTokens.unlock(tokenId, userId, hasher.hash(unlockKey), now, request)) {
            throw MobileUnlockRefusedException()
        }
        audit.record(AuditEntry(null, userId, "token.unlocked", "token", tokenId.toString()))
        return now
    }

    private companion object {
        const val UNLOCK_LIMIT = 30
        const val MAX_REQUEST_ID_LENGTH = 64
    }
}
