// SPDX-License-Identifier: Apache-2.0

package run.ostrom.server.identity.fakes

import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.MagicLink
import run.ostrom.server.identity.domain.MagicLinksRepository
import run.ostrom.server.identity.domain.Mail
import run.ostrom.server.identity.domain.MailNotSentException
import run.ostrom.server.identity.domain.MailSender
import run.ostrom.server.identity.domain.OAuthAttempt
import run.ostrom.server.identity.domain.OAuthAttemptsRepository
import run.ostrom.server.identity.domain.OAuthProvider
import run.ostrom.server.identity.domain.ProviderRejectedException
import run.ostrom.server.identity.domain.VerifiedIdentity
import kotlin.time.Instant

class InMemoryOAuthAttemptsRepository : OAuthAttemptsRepository {
    val attempts = mutableMapOf<String, OAuthAttempt>()
    private val used = mutableSetOf<String>()

    override suspend fun create(attempt: OAuthAttempt, stateHash: String) {
        attempts[stateHash] = attempt
    }

    override suspend fun consume(stateHash: String, now: Instant): OAuthAttempt? {
        val attempt = attempts[stateHash]
        val usable = attempt != null && stateHash !in used && attempt.expiresAt > now
        if (usable) used += stateHash
        return attempt.takeIf { usable }
    }
}

class InMemoryMagicLinksRepository : MagicLinksRepository {
    val links = mutableMapOf<String, MagicLink>()
    val browsers = mutableMapOf<String, String>()
    private val used = mutableSetOf<String>()

    override suspend fun create(link: MagicLink, tokenHash: String, browserHash: String) {
        links[tokenHash] = link
        browsers[tokenHash] = browserHash
    }

    override suspend fun consume(tokenHash: String, browserHash: String, now: Instant): MagicLink? {
        val usable = pending(tokenHash, now) && browsers[tokenHash] == browserHash
        if (usable) used += tokenHash
        return links[tokenHash].takeIf { usable }
    }

    override suspend fun pending(tokenHash: String, now: Instant): Boolean {
        val link = links[tokenHash]
        return link != null && tokenHash !in used && link.expiresAt > now
    }
}

class RecordingMailSender(var fail: Boolean = false) : MailSender {
    val sent = mutableListOf<Mail>()

    override suspend fun send(mail: Mail) {
        if (fail) throw MailNotSentException()
        sent += mail
    }
}

/**
 * A provider that accepts the code `good` and answers [identity]; the consent URL lists what it was given, so tests
 * read the state back from it.
 */
class FakeOAuthProvider(
    override val provider: IdentityProvider,
    var identity: VerifiedIdentity = VerifiedIdentity(provider, "42", "ada@example.com", true, "Ada"),
) : OAuthProvider {
    val exchanges = mutableListOf<List<String>>()

    override fun authorizationUrl(
        state: String,
        codeChallenge: String,
        nonce: String,
        redirectUri: String,
        reauthenticate: Boolean,
    ): String = "https://idp.example/authorize?state=$state&challenge=$codeChallenge&nonce=$nonce" +
        "&redirect=$redirectUri&reauth=$reauthenticate"

    override suspend fun identity(
        code: String,
        codeVerifier: String,
        nonce: String,
        redirectUri: String,
    ): VerifiedIdentity {
        exchanges += listOf(code, codeVerifier, nonce, redirectUri)
        if (code != "good") throw ProviderRejectedException("bad code.")
        return identity
    }
}
