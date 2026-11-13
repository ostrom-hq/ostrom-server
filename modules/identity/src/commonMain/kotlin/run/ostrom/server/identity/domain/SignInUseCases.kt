// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.domain

import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * The sign-in providers this instance offers: those whose credentials are configured. A provider that is not here
 * answers 404 at every step, so an instance without Google simply has no Google button.
 *
 * @property oauth The OAuth and OIDC providers, by provider.
 * @property email True when e-mailed sign-in links can be sent (a mail sender is configured).
 */
data class SignInMethods(val oauth: Map<IdentityProvider, OAuthProvider>, val email: Boolean) {
    /** The keys of the offered methods, for the login page (`github`, `google`, `email`). */
    val keys: List<String>
        get() = oauth.keys.map { it.key }.sorted() + if (email) listOf(IdentityProvider.EMAIL.key) else emptyList()
}

/**
 * What the sign-in use cases share: secrets and their hashes, the rate limiter, the clock and the callback URLs.
 *
 * @property generator Generates states, PKCE verifiers, nonces and link tokens.
 * @property hasher Hashes them, and computes the PKCE challenge.
 * @property limiter Limits sign-ins per address and per recipient.
 * @property clock The clock.
 * @property callbackUrl The server's callback URL of a provider (the same at the start and at the end).
 */
class SignInServices(
    val generator: SecretGenerator,
    val hasher: SecretHasher,
    val limiter: RateLimiter,
    val clock: Clock,
    val callbackUrl: (IdentityProvider) -> String,
)

/** Starts a sign-in with GitHub, Google or another OAuth provider. */
interface StartOAuthSignInUseCase {
    /**
     * Prepares the consent page: a state for the browser's cookie, a PKCE verifier and a nonce kept on the server.
     *
     * @param provider The provider key (`github`, `google`).
     * @param redirect The console path to return to after signing in; anything unsafe becomes `/`.
     * @param source Who is asking (the client address), for the rate limit.
     * @param linkTo The signed-in user who links this provider to their account (S2-07), or null for a sign-in.
     * @param reauthenticate Whether the person signs in again before a yes (S2-54): the provider must not reuse an
     *   old sign-in silently.
     *
     * @return The state and the consent page URL.
     *
     * @throws ProviderNotConfiguredException When the provider is unknown or not configured.
     * @throws RateLimitedException When the address starts too many sign-ins.
     */
    suspend operator fun invoke(
        provider: String,
        redirect: String?,
        source: String,
        linkTo: Uuid? = null,
        reauthenticate: Boolean = false,
    ): StartedOAuth

    /**
     * Prepares the consent page of a sign-in the phone app starts (S2-24): the callback returns a one-time code to
     * the app's deep link instead of opening a browser session. The provider always asks again: a new phone is never
     * signed in silently.
     *
     * @param provider The provider key (`github`, `google`).
     * @param source Who is asking (the client address), for the rate limit.
     * @param mobile The app ([mobileClientOf]).
     *
     * @return The state and the consent page URL.
     *
     * @throws ProviderNotConfiguredException When the provider is unknown or not configured.
     * @throws RateLimitedException When the address starts too many sign-ins.
     */
    suspend fun mobile(provider: String, source: String, mobile: MobileClient): StartedOAuth
}

/**
 * What an attempt carries besides its provider.
 *
 * @property redirect The console path to return to.
 * @property linkTo The user who links the provider, if any.
 * @property reauthenticate Whether the provider must ask again.
 * @property mobile The phone app that started it, if any.
 */
private data class AttemptOptions(
    val redirect: String?,
    val linkTo: Uuid? = null,
    val reauthenticate: Boolean = false,
    val mobile: MobileClient? = null,
)

/**
 * [StartOAuthSignInUseCase].
 *
 * @param methods The configured providers.
 * @param attempts Where attempts are kept.
 * @param services Secrets, hashes, the rate limit per address, the clock and the callback URLs.
 */
class StartOAuthSignInUseCaseImpl(
    private val methods: SignInMethods,
    private val attempts: OAuthAttemptsRepository,
    private val services: SignInServices,
) : StartOAuthSignInUseCase {
    override suspend fun invoke(
        provider: String,
        redirect: String?,
        source: String,
        linkTo: Uuid?,
        reauthenticate: Boolean,
    ): StartedOAuth = start(provider, source, AttemptOptions(redirect, linkTo, reauthenticate))

    override suspend fun mobile(provider: String, source: String, mobile: MobileClient): StartedOAuth =
        start(provider, source, AttemptOptions(null, reauthenticate = true, mobile = mobile))

    private suspend fun start(provider: String, source: String, options: AttemptOptions): StartedOAuth {
        val oauth = configuredProvider(methods, provider)
        val (generator, hasher) = services.generator to services.hasher
        val key = "oauth-start:${rateLimitSource(source)}"
        if (!services.limiter.tryAcquire(key, START_LIMIT, LIMIT_WINDOW_MINUTES.minutes)) {
            throw RateLimitedException()
        }
        val state = generator.secret()
        val verifier = generator.secret()
        val nonce = generator.secret()
        val now = services.clock.nowMillis()
        attempts.create(
            OAuthAttempt(
                id = Uuid.random(),
                provider = oauth.provider,
                connectionId = null,
                codeVerifier = verifier,
                nonce = nonce,
                redirectPath = safeRedirectPath(options.redirect),
                createdAt = now,
                expiresAt = now + OAUTH_STATE_LIFETIME,
                linkUserId = options.linkTo,
                mobile = options.mobile,
            ),
            hasher.hash(state),
        )
        val callback = services.callbackUrl(oauth.provider)
        val challenge = hasher.pkceChallenge(verifier)
        val url = oauth.authorizationUrl(state, challenge, nonce, callback, options.reauthenticate)
        return StartedOAuth(state, url)
    }

    private companion object {
        const val START_LIMIT = 30
        const val LIMIT_WINDOW_MINUTES = 10
    }
}

/** Completes a sign-in when the provider sends the browser back. */
interface CompleteOAuthSignInUseCase {
    /**
     * Checks the state against the browser's cookie, takes the attempt (once), exchanges the code with PKCE, then
     * signs the person in: their account, a merged one (same e-mail, proven by an authority for it) or a new one. An
     * attempt started to link a provider links the identity to its user instead.
     *
     * @param provider The provider key in the callback URL.
     * @param state The state the provider returned.
     * @param browserState The state in the browser's cookie, set by [StartOAuthSignInUseCase].
     * @param code The authorization code.
     * @param signUpToken The setup link's or invitation's token the browser gave before signing in (`grantSignUp`),
     *   which lets an invite-only instance create the account.
     *
     * @return The user, a new browser session and where to go.
     *
     * @throws ProviderNotConfiguredException When the provider is unknown or not configured.
     * @throws InvalidSignInStateException When the state is missing, does not match the cookie, is unknown, used or
     *   expired, or belongs to another provider.
     * @throws ProviderRejectedException When the provider refuses the code.
     * @throws AccountLinkRequiredException When the provider asserts the address of another account (S2-07).
     * @throws IdentityTakenException When the identity to link signs another user in.
     * @throws SignUpClosedException When the account would be new and the instance does not admit it.
     */
    suspend operator fun invoke(
        provider: String,
        state: String?,
        browserState: String?,
        code: String?,
        signUpToken: String? = null,
    ): CompletedSignIn
}

/**
 * [CompleteOAuthSignInUseCase].
 *
 * @param methods The configured providers.
 * @param attempts Where attempts are kept.
 * @param services Hashes the state; the clock and the callback URLs (the same as at the start).
 * @param resolveUser Finds, merges or creates the account.
 * @param linkIdentity Links an identity to the user who started the attempt to link it.
 * @param startSession Opens the browser session.
 */
class CompleteOAuthSignInUseCaseImpl(
    private val methods: SignInMethods,
    private val attempts: OAuthAttemptsRepository,
    private val services: SignInServices,
    private val resolveUser: ResolveUserUseCase,
    private val linkIdentity: LinkIdentityUseCase,
    private val startSession: StartWebSessionUseCase,
) : CompleteOAuthSignInUseCase {
    override suspend fun invoke(
        provider: String,
        state: String?,
        browserState: String?,
        code: String?,
        signUpToken: String?,
    ): CompletedSignIn {
        val (oauth, attempt) = takeAttempt(methods, attempts, services, TakenState(provider, state, browserState))
        // A sign-in the phone app started ends in the app, never in a browser session.
        if (attempt.mobile != null) throw InvalidSignInStateException()
        if (code.isNullOrEmpty()) throw ProviderRejectedException("no authorization code.")
        val identity = oauth.identity(code, attempt.codeVerifier, attempt.nonce, services.callbackUrl(oauth.provider))
        val linkTo = attempt.linkUserId
        val user = if (linkTo != null) {
            linkIdentity(linkTo, identity)
        } else {
            resolveUser(identity, SignUpGrant.Presented(signUpToken))
        }
        return CompletedSignIn(user, startSession(user.id), attempt.redirectPath)
    }
}

/** Sends an e-mailed sign-in link. */
interface RequestMagicLinkUseCase {
    /**
     * Sends a sign-in link to an address. The answer is the same whether or not an account exists (no enumeration):
     * the link signs an existing user in, or creates the account at its first use.
     *
     * @param email The address, as typed.
     * @param redirect The console path to return to; anything unsafe becomes `/`.
     * @param source Who is asking (the client address), for the rate limit.
     * @param browserNonce The nonce the asking browser already holds from an earlier link, if any: kept, so that each
     *   link it asked for works in it.
     *
     * @return The browser's nonce, which only that browser keeps (a cookie): the link signs in only where it is
     *   presented (web audit n°2 M4). Given even when the recipient's silent limit sends nothing.
     *
     * @throws InvalidEmailException When the address is not valid.
     * @throws ProviderNotConfiguredException When no mail sender is configured.
     * @throws RateLimitedException When the client asks for too many links.
     * @throws MailNotSentException When the mail provider fails.
     */
    suspend operator fun invoke(email: String, redirect: String?, source: String, browserNonce: String?): String
}

/**
 * [RequestMagicLinkUseCase].
 *
 * @param methods The configured methods.
 * @param links Where links are kept.
 * @param mail Sends the e-mail.
 * @param services Generates and hashes the link token; limits requests per address and per recipient; the clock.
 * @param template Builds the e-mail from the recipient and the link.
 * @param linkUrl The console URL the e-mail points to, for a token.
 */
class RequestMagicLinkUseCaseImpl(
    private val methods: SignInMethods,
    private val links: MagicLinksRepository,
    private val mail: MailSender,
    private val services: SignInServices,
    private val template: (to: String, link: String) -> Mail,
    private val linkUrl: (token: String) -> String,
) : RequestMagicLinkUseCase {
    override suspend fun invoke(email: String, redirect: String?, source: String, browserNonce: String?): String {
        val limiter = services.limiter
        if (!methods.email) throw ProviderNotConfiguredException()
        val address = normalizeEmail(email)
        val key = "magic-link:${rateLimitSource(source)}"
        if (!limiter.tryAcquire(key, SOURCE_LIMIT, LIMIT_WINDOW_MINUTES.minutes)) {
            throw RateLimitedException()
        }
        // Per recipient, the limit is silent: an error would tell a stranger the address is being used, and a flood
        // of e-mails to one person stops at a few.
        val nonce = browserNonce?.takeIf { it.length in 1..MAX_NONCE } ?: services.generator.secret()
        if (!limiter.tryAcquire("magic-link-to:$address", RECIPIENT_LIMIT, LIMIT_WINDOW_MINUTES.minutes)) return nonce
        val token = services.generator.secret()
        val now = services.clock.nowMillis()
        val link = MagicLink(Uuid.random(), address, safeRedirectPath(redirect), now, now + MAGIC_LINK_LIFETIME)
        links.create(link, services.hasher.hash(token), services.hasher.hash(nonce))
        mail.send(template(address, linkUrl(token)))
        return nonce
    }

    private companion object {
        const val SOURCE_LIMIT = 10
        const val RECIPIENT_LIMIT = 3
        const val MAX_NONCE = 128
        const val LIMIT_WINDOW_MINUTES = 15
    }
}

/** Signs in with an e-mailed link. */
interface VerifyMagicLinkUseCase {
    /**
     * Uses a link (once): the address is proven, so the user is found by it, or created.
     *
     * @param token The token of the link.
     * @param browserNonce The nonce of the browser presenting it (its cookie), null when it holds none.
     * @param signUpToken The setup link's or invitation's token the browser gave before signing in (`grantSignUp`).
     *
     * @return The user, a new browser session and where to go.
     *
     * @throws InvalidMagicLinkException When the link is unknown, used or expired.
     * @throws MagicLinkOtherBrowserException When the link is valid but another browser asked for it: it is not used.
     * @throws SignUpClosedException When the account would be new and the instance does not admit it.
     */
    suspend operator fun invoke(token: String, browserNonce: String?, signUpToken: String? = null): CompletedSignIn
}

/**
 * [VerifyMagicLinkUseCase].
 *
 * @param links Where links are kept.
 * @param hasher Hashes the token.
 * @param resolveUser Finds, merges or creates the account.
 * @param startSession Opens the browser session.
 * @param clock The clock.
 */
class VerifyMagicLinkUseCaseImpl(
    private val links: MagicLinksRepository,
    private val hasher: SecretHasher,
    private val resolveUser: ResolveUserUseCase,
    private val startSession: StartWebSessionUseCase,
    private val clock: Clock,
) : VerifyMagicLinkUseCase {
    override suspend fun invoke(token: String, browserNonce: String?, signUpToken: String?): CompletedSignIn {
        if (token.isEmpty() || token.length > MAX_TOKEN) throw InvalidMagicLinkException()
        val tokenHash = hasher.hash(token)
        val now = clock.nowMillis()
        val presented = browserNonce?.takeIf { it.length in 1..MAX_TOKEN }
        val link = presented?.let { links.consume(tokenHash, hasher.hash(it), now) }
        // A link still usable here was asked for by another browser: told so, and kept for that browser.
        if (link == null) {
            throw if (links.pending(tokenHash, now)) MagicLinkOtherBrowserException() else InvalidMagicLinkException()
        }
        val identity = VerifiedIdentity(IdentityProvider.EMAIL, link.email, link.email, true)
        val user = resolveUser(identity, SignUpGrant.Presented(signUpToken))
        return CompletedSignIn(user, startSession(user.id), link.redirectPath)
    }

    private companion object {
        const val MAX_TOKEN = 128
    }
}

/**
 * The state of a provider's callback, as the browser brings it back.
 *
 * @property provider The provider key in the callback URL.
 * @property state The state the provider returned.
 * @property browserState The state in the browser's cookie, set at the start.
 */
internal data class TakenState(val provider: String, val state: String?, val browserState: String?)

/**
 * Checks a callback's state against the browser's cookie and takes its attempt, once.
 *
 * @param methods The configured providers.
 * @param attempts Where attempts are kept.
 * @param services Hashes the state; the clock.
 * @param taken The provider and the two states.
 *
 * @return The provider and the attempt, now used.
 *
 * @throws ProviderNotConfiguredException When the provider is unknown or not configured.
 * @throws InvalidSignInStateException When the state is missing, does not match the cookie, is unknown, used or
 *   expired, or belongs to another provider.
 */
internal suspend fun takeAttempt(
    methods: SignInMethods,
    attempts: OAuthAttemptsRepository,
    services: SignInServices,
    taken: TakenState,
): Pair<OAuthProvider, OAuthAttempt> {
    val oauth = configuredProvider(methods, taken.provider)
    val state = taken.state
    val browserState = taken.browserState
    if (state.isNullOrEmpty() || browserState == null || !constantTimeEquals(state, browserState)) {
        throw InvalidSignInStateException()
    }
    val attempt = attempts.consume(services.hasher.hash(state), services.clock.nowMillis())
    if (attempt == null || attempt.provider != oauth.provider) throw InvalidSignInStateException()
    return oauth to attempt
}

private fun configuredProvider(methods: SignInMethods, key: String): OAuthProvider {
    val provider = IdentityProvider.entries.firstOrNull { it.key == key } ?: throw ProviderNotConfiguredException()
    return methods.oauth[provider] ?: throw ProviderNotConfiguredException()
}
