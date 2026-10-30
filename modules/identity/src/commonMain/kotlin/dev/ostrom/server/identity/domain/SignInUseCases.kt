package dev.ostrom.server.identity.domain

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
    val keys: List<String> get() = oauth.keys.map { it.key }.sorted() + if (email) listOf(IdentityProvider.EMAIL.key) else emptyList()
}

/** Starts a sign-in with GitHub, Google or another OAuth provider. */
interface StartOAuthSignInUseCase {
    /**
     * Prepares the consent page: a state for the browser's cookie, a PKCE verifier and a nonce kept on the server.
     *
     * @param provider The provider key (`github`, `google`).
     * @param redirect The console path to return to after signing in; anything unsafe becomes `/`.
     * @param source Who is asking (the client address), for the rate limit.
     * @param linkTo The signed-in user who links this provider to their account (S2-07), or null for a sign-in.
     *
     * @return The state and the consent page URL.
     *
     * @throws ProviderNotConfiguredException When the provider is unknown or not configured.
     * @throws RateLimitedException When the address starts too many sign-ins.
     */
    suspend operator fun invoke(provider: String, redirect: String?, source: String, linkTo: Uuid? = null): StartedOAuth
}

/**
 * [StartOAuthSignInUseCase].
 *
 * @param methods The configured providers.
 * @param attempts Where attempts are kept.
 * @param generator Generates the state, verifier and nonce.
 * @param hasher Hashes the state and computes the PKCE challenge.
 * @param limiter Limits sign-ins per address.
 * @param clock The clock.
 * @param callbackUrl The server's callback URL of a provider.
 */
class StartOAuthSignInUseCaseImpl(
    private val methods: SignInMethods,
    private val attempts: OAuthAttemptsRepository,
    private val generator: SecretGenerator,
    private val hasher: SecretHasher,
    private val limiter: RateLimiter,
    private val clock: Clock,
    private val callbackUrl: (IdentityProvider) -> String,
) : StartOAuthSignInUseCase {
    override suspend fun invoke(provider: String, redirect: String?, source: String, linkTo: Uuid?): StartedOAuth {
        val oauth = configuredProvider(methods, provider)
        if (!limiter.tryAcquire("oauth-start:$source", START_LIMIT, LIMIT_WINDOW)) throw RateLimitedException()
        val state = generator.secret()
        val verifier = generator.secret()
        val nonce = generator.secret()
        val now = clock.nowMillis()
        attempts.create(
            OAuthAttempt(
                id = Uuid.random(),
                provider = oauth.provider,
                connectionId = null,
                codeVerifier = verifier,
                nonce = nonce,
                redirectPath = safeRedirectPath(redirect),
                createdAt = now,
                expiresAt = now + OAUTH_STATE_LIFETIME,
                linkUserId = linkTo,
            ),
            hasher.hash(state),
        )
        val url = oauth.authorizationUrl(state, hasher.pkceChallenge(verifier), nonce, callbackUrl(oauth.provider))
        return StartedOAuth(state, url)
    }

    private companion object {
        const val START_LIMIT = 30
        val LIMIT_WINDOW = 10.minutes
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
     *
     * @return The user, a new browser session and where to go.
     *
     * @throws ProviderNotConfiguredException When the provider is unknown or not configured.
     * @throws InvalidSignInStateException When the state is missing, does not match the cookie, is unknown, used or
     *   expired, or belongs to another provider.
     * @throws ProviderRejectedException When the provider refuses the code.
     * @throws AccountLinkRequiredException When the provider asserts the address of another account (S2-07).
     * @throws IdentityTakenException When the identity to link signs another user in.
     */
    suspend operator fun invoke(provider: String, state: String?, browserState: String?, code: String?): CompletedSignIn
}

/**
 * [CompleteOAuthSignInUseCase].
 *
 * @param methods The configured providers.
 * @param attempts Where attempts are kept.
 * @param hasher Hashes the state.
 * @param resolveUser Finds, merges or creates the account.
 * @param linkIdentity Links an identity to the user who started the attempt to link it.
 * @param startSession Opens the browser session.
 * @param clock The clock.
 * @param callbackUrl The server's callback URL of a provider (the same as at the start).
 */
class CompleteOAuthSignInUseCaseImpl(
    private val methods: SignInMethods,
    private val attempts: OAuthAttemptsRepository,
    private val hasher: SecretHasher,
    private val resolveUser: ResolveUserUseCase,
    private val linkIdentity: LinkIdentityUseCase,
    private val startSession: StartWebSessionUseCase,
    private val clock: Clock,
    private val callbackUrl: (IdentityProvider) -> String,
) : CompleteOAuthSignInUseCase {
    override suspend fun invoke(provider: String, state: String?, browserState: String?, code: String?): CompletedSignIn {
        val oauth = configuredProvider(methods, provider)
        if (state.isNullOrEmpty() || browserState == null || !constantTimeEquals(state, browserState)) {
            throw InvalidSignInStateException()
        }
        val attempt = attempts.consume(hasher.hash(state), clock.nowMillis())
        if (attempt == null || attempt.provider != oauth.provider) throw InvalidSignInStateException()
        if (code.isNullOrEmpty()) throw ProviderRejectedException("no authorization code.")
        val identity = oauth.identity(code, attempt.codeVerifier, attempt.nonce, callbackUrl(oauth.provider))
        val linkTo = attempt.linkUserId
        val user = if (linkTo != null) linkIdentity(linkTo, identity) else resolveUser(identity)
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
     *
     * @throws InvalidEmailException When the address is not valid.
     * @throws ProviderNotConfiguredException When no mail sender is configured.
     * @throws RateLimitedException When the client asks for too many links.
     * @throws MailNotSentException When the mail provider fails.
     */
    suspend operator fun invoke(email: String, redirect: String?, source: String)
}

/**
 * [RequestMagicLinkUseCase].
 *
 * @param methods The configured methods.
 * @param links Where links are kept.
 * @param mail Sends the e-mail.
 * @param generator Generates the link token.
 * @param hasher Hashes the token.
 * @param limiter Limits requests per address and per recipient.
 * @param clock The clock.
 * @param template Builds the e-mail from the recipient and the link.
 * @param linkUrl The console URL the e-mail points to, for a token.
 */
class RequestMagicLinkUseCaseImpl(
    private val methods: SignInMethods,
    private val links: MagicLinksRepository,
    private val mail: MailSender,
    private val generator: SecretGenerator,
    private val hasher: SecretHasher,
    private val limiter: RateLimiter,
    private val clock: Clock,
    private val template: (to: String, link: String) -> Mail,
    private val linkUrl: (token: String) -> String,
) : RequestMagicLinkUseCase {
    override suspend fun invoke(email: String, redirect: String?, source: String) {
        if (!methods.email) throw ProviderNotConfiguredException()
        val address = normalizeEmail(email)
        if (!limiter.tryAcquire("magic-link:$source", SOURCE_LIMIT, LIMIT_WINDOW)) throw RateLimitedException()
        // Per recipient, the limit is silent: an error would tell a stranger the address is being used, and a flood
        // of e-mails to one person stops at a few.
        if (!limiter.tryAcquire("magic-link-to:$address", RECIPIENT_LIMIT, LIMIT_WINDOW)) return
        val token = generator.secret()
        val now = clock.nowMillis()
        links.create(MagicLink(Uuid.random(), address, safeRedirectPath(redirect), now, now + MAGIC_LINK_LIFETIME), hasher.hash(token))
        mail.send(template(address, linkUrl(token)))
    }

    private companion object {
        const val SOURCE_LIMIT = 10
        const val RECIPIENT_LIMIT = 3
        val LIMIT_WINDOW = 15.minutes
    }
}

/** Signs in with an e-mailed link. */
interface VerifyMagicLinkUseCase {
    /**
     * Uses a link (once): the address is proven, so the user is found by it, or created.
     *
     * @param token The token of the link.
     *
     * @return The user, a new browser session and where to go.
     *
     * @throws InvalidMagicLinkException When the link is unknown, used or expired.
     */
    suspend operator fun invoke(token: String): CompletedSignIn
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
    override suspend fun invoke(token: String): CompletedSignIn {
        if (token.isEmpty() || token.length > MAX_TOKEN) throw InvalidMagicLinkException()
        val link = links.consume(hasher.hash(token), clock.nowMillis()) ?: throw InvalidMagicLinkException()
        val user = resolveUser(VerifiedIdentity(IdentityProvider.EMAIL, link.email, link.email, true))
        return CompletedSignIn(user, startSession(user.id), link.redirectPath)
    }

    private companion object {
        const val MAX_TOKEN = 128
    }
}

private fun configuredProvider(methods: SignInMethods, key: String): OAuthProvider {
    val provider = IdentityProvider.entries.firstOrNull { it.key == key } ?: throw ProviderNotConfiguredException()
    return methods.oauth[provider] ?: throw ProviderNotConfiguredException()
}
