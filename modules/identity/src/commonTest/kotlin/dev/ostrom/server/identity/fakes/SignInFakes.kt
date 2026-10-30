// SPDX-License-Identifier: AGPL-3.0-only

package dev.ostrom.server.identity.fakes

import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.MagicLink
import dev.ostrom.server.identity.domain.MagicLinksRepository
import dev.ostrom.server.identity.domain.Mail
import dev.ostrom.server.identity.domain.MailNotSentException
import dev.ostrom.server.identity.domain.MailSender
import dev.ostrom.server.identity.domain.OAuthAttempt
import dev.ostrom.server.identity.domain.OAuthAttemptsRepository
import dev.ostrom.server.identity.domain.OAuthProvider
import dev.ostrom.server.identity.domain.ProviderRejectedException
import dev.ostrom.server.identity.domain.VerifiedIdentity
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
    private val used = mutableSetOf<String>()

    override suspend fun create(link: MagicLink, tokenHash: String) {
        links[tokenHash] = link
    }

    override suspend fun consume(tokenHash: String, now: Instant): MagicLink? {
        val link = links[tokenHash]
        val usable = link != null && tokenHash !in used && link.expiresAt > now
        if (usable) used += tokenHash
        return link.takeIf { usable }
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

    override fun authorizationUrl(state: String, codeChallenge: String, nonce: String, redirectUri: String): String =
        "https://idp.example/authorize?state=$state&challenge=$codeChallenge&nonce=$nonce&redirect=$redirectUri"

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
