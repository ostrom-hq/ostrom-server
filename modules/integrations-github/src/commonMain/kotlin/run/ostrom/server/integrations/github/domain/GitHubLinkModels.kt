// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The instance App's OAuth client, as the members' links use it: the device flow needs the id alone, the code
 * exchange, the refresh and the revocation need the secret too.
 *
 * @property clientId The client id (`Iv…`).
 * @property clientSecret The client secret: never in a log nor a dump.
 */
class GitHubOAuthApp(val clientId: String, val clientSecret: String) {
    override fun toString(): String = "GitHubOAuthApp(clientId=$clientId)"
}

/** @return The App's OAuth client, when both its id and its secret are known. */
fun GitHubAppCredentials.oauthApp(): GitHubOAuthApp? {
    val id = clientId
    val secret = clientSecret
    return if (id == null || secret == null) null else GitHubOAuthApp(id, secret)
}

/**
 * A GitHub account, as its user-to-server token reads it (`GET /user`).
 *
 * @property userId Its id, which never changes.
 * @property login Its login, which can.
 */
data class GitHubIdentity(val userId: Long, val login: String)

/**
 * A GitHub App user-to-server token pair. The expiries are null when the App does not expire user tokens.
 *
 * @property accessToken The token `git` and `gh` use (`ghu_…`).
 * @property accessExpiresAt When it expires.
 * @property refreshToken The rotating refresh token (`ghr_…`), spent once.
 * @property refreshExpiresAt When it expires.
 */
class GitHubTokens(
    val accessToken: String,
    val accessExpiresAt: Instant?,
    val refreshToken: String?,
    val refreshExpiresAt: Instant?,
) {
    override fun toString(): String =
        "GitHubTokens(accessExpiresAt=$accessExpiresAt, refreshExpiresAt=$refreshExpiresAt)"
}

/**
 * The state of a stored link.
 *
 * @property wire Its name in the API and the database.
 */
enum class GitHubLinkState(val wire: String) {
    /** Usable: the token is valid or can be refreshed. */
    LINKED("linked"),

    /** GitHub refused the refresh token, or the instance's App changed: the member links again. */
    RECONNECT_REQUIRED("reconnect_required"),
    ;

    /** Readers of the enclosing type. */
    companion object {
        /** The name of "no link" in the API. */
        const val NOT_LINKED: String = "not_linked"

        /**
         * @param wire A stored name.
         * @return The state; an unknown name needs a new link.
         */
        fun of(wire: String): GitHubLinkState = entries.firstOrNull { it.wire == wire } ?: RECONNECT_REQUIRED
    }
}

/**
 * One member's link, without any token.
 *
 * @property orgId The organization: a link is one member's in one organization.
 * @property memberId The member.
 * @property identity The GitHub account.
 * @property clientId The App's client that issued the tokens: only that App refreshes or revokes them.
 * @property state Whether it is usable.
 * @property accessExpiresAt When the access token expires.
 * @property refreshExpiresAt When the refresh token expires.
 * @property linkedAt When the member linked it.
 * @property refreshedAt When the tokens were last renewed.
 */
data class GitHubLink(
    val orgId: Uuid,
    val memberId: Uuid,
    val identity: GitHubIdentity,
    val clientId: String,
    val state: GitHubLinkState,
    val accessExpiresAt: Instant?,
    val refreshExpiresAt: Instant?,
    val linkedAt: Instant,
    val refreshedAt: Instant,
)

/**
 * A link just stored.
 *
 * @property link The link.
 * @property replaced The access token of the link it replaced, to revoke.
 */
class GitHubSavedLink(val link: GitHubLink, val replaced: GitHubRevocable?) {
    override fun toString(): String = "GitHubSavedLink(${link.identity.login})"
}

/**
 * A member's link as the API shows it.
 *
 * @property memberId The member.
 * @property appConfigured Whether the instance's App can link accounts (its OAuth client is known).
 * @property link The link, or null when the member never linked or unlinked.
 * @property state The link's state, null without one; a link made with another App than the current one is
 *   [GitHubLinkState.RECONNECT_REQUIRED], since it can never be refreshed.
 */
data class GitHubLinkStatus(
    val memberId: Uuid,
    val appConfigured: Boolean,
    val link: GitHubLink?,
    val state: GitHubLinkState?,
) {
    /** Builds a status. */
    companion object {
        /**
         * @param memberId The member.
         * @param app The instance App's OAuth client, or null.
         * @param link The stored link, or null.
         * @return The status.
         */
        fun of(memberId: Uuid, app: GitHubOAuthApp?, link: GitHubLink?): GitHubLinkStatus =
            GitHubLinkStatus(
                memberId,
                app != null,
                link,
                when {
                    link == null -> null
                    app == null || app.clientId != link.clientId -> GitHubLinkState.RECONNECT_REQUIRED
                    else -> link.state
                },
            )
    }
}

/**
 * How a pending link was started.
 *
 * @property wire Its name in the database.
 */
enum class GitHubLinkKind(val wire: String) {
    /** GitHub's device flow (`ostrom github link`, the app). */
    DEVICE("device"),

    /** GitHub's web flow (the console). */
    WEB("web"),
}

/**
 * A pending link, bound to one member. [secret] is GitHub's device code (device flow) or the PKCE verifier (web
 * flow), stored sealed; [binding] is the SHA-256 of the browser session that started a web link, which alone may
 * finish it.
 *
 * @property id The attempt: a UUID for a device link, the SHA-256 of the state for a web link.
 * @property orgId The organization.
 * @property memberId The member the link will be.
 * @property kind How it was started.
 * @property secret The device code or the PKCE verifier.
 * @property binding The hash of the browser session of a web link.
 * @property pollIntervalSeconds The interval GitHub asked for, for a device link.
 * @property nextPollAt When GitHub may be asked again.
 * @property expiresAt When it stops being accepted.
 */
@Suppress("LongParameterList") // A stored row: every column is a property.
class GitHubLinkAttempt(
    val id: String,
    val orgId: Uuid,
    val memberId: Uuid,
    val kind: GitHubLinkKind,
    val secret: String,
    val binding: String?,
    val pollIntervalSeconds: Int,
    val nextPollAt: Instant,
    val expiresAt: Instant,
) {
    override fun toString(): String = "GitHubLinkAttempt(id=$id, memberId=$memberId, kind=$kind)"
}

/**
 * GitHub's answer to a device authorization.
 *
 * @property deviceCode The device code: a secret, kept on the server.
 * @property userCode The code the member enters on GitHub.
 * @property verificationUri Where they enter it.
 * @property expiresInSeconds How long the codes last.
 * @property intervalSeconds How often GitHub may be polled.
 */
class GitHubDeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
) {
    override fun toString(): String = "GitHubDeviceAuthorization(userCode=$userCode, uri=$verificationUri)"
}

/** One answer of GitHub to a device poll. */
sealed interface GitHubDevicePoll {
    /** The member has not entered the code yet. */
    data object Pending : GitHubDevicePoll

    /**
     * GitHub asks for a longer interval.
     *
     * @property intervalSeconds The new interval.
     */
    data class SlowDown(val intervalSeconds: Int) : GitHubDevicePoll

    /** The code expired. */
    data object Expired : GitHubDevicePoll

    /** The member refused. */
    data object Denied : GitHubDevicePoll

    /**
     * The member authorized the App.
     *
     * @property tokens The tokens.
     */
    class Authorized(val tokens: GitHubTokens) : GitHubDevicePoll
}

/** GitHub's answer to a refresh. */
sealed interface GitHubRefresh {
    /**
     * New tokens; the old refresh token is spent.
     *
     * @property tokens The new pair.
     */
    class Refreshed(val tokens: GitHubTokens) : GitHubRefresh

    /** GitHub refused the refresh token (revoked, expired or already used): the member must link again. */
    data object Rejected : GitHubRefresh
}

/**
 * A device link just started, as the member sees it.
 *
 * @property id The attempt to poll.
 * @property userCode The code to enter.
 * @property verificationUri Where to enter it.
 * @property expiresAt When it ends.
 * @property intervalSeconds How often to poll.
 */
data class GitHubDeviceLink(
    val id: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAt: Instant,
    val intervalSeconds: Int,
)

/** Where a device link is. */
sealed interface GitHubDeviceLinkProgress {
    /**
     * Not yet.
     *
     * @property intervalSeconds When to poll again.
     */
    data class Pending(val intervalSeconds: Int) : GitHubDeviceLinkProgress

    /**
     * Linked.
     *
     * @property status The member's link.
     */
    data class Linked(val status: GitHubLinkStatus) : GitHubDeviceLinkProgress

    /** The code expired: start again. */
    data object Expired : GitHubDeviceLinkProgress

    /** The member refused on GitHub. */
    data object Denied : GitHubDeviceLinkProgress
}

/** The current token of a link, or the right to refresh it ([GitHubLinkStore.claim]). */
sealed interface GitHubTokenClaim {
    /** No link. */
    data object NotLinked : GitHubTokenClaim

    /**
     * The link needs a new one.
     *
     * @property endedBecause Why this claim ended it (`refresh_expired`, `tokens_unreadable`), journaled once; null
     *   when it had ended before.
     */
    data class ReconnectRequired(val endedBecause: String? = null) : GitHubTokenClaim

    /** Another replica is refreshing this link: ask again shortly. */
    data object Wait : GitHubTokenClaim

    /**
     * A token to hand out.
     *
     * @property link The link.
     * @property accessToken Its token.
     */
    class Ready(val link: GitHubLink, val accessToken: String) : GitHubTokenClaim {
        override fun toString(): String = "Ready(${link.identity.login})"
    }

    /**
     * This caller owns the refresh: [tokenVersion] and [startedAt] fence its write.
     *
     * @property link The link.
     * @property accessToken The current token, handed out if the refresh fails while it is still valid.
     * @property refreshToken The refresh token to spend.
     * @property tokenVersion The version of the stored pair.
     * @property startedAt When the claim was taken.
     */
    class Refresh(
        val link: GitHubLink,
        val accessToken: String,
        val refreshToken: String,
        val tokenVersion: Int,
        val startedAt: Instant,
    ) : GitHubTokenClaim {
        override fun toString(): String = "Refresh(${link.identity.login})"
    }
}

/**
 * An access token to revoke, with the App that issued it: only that App's credentials can revoke it.
 *
 * @property clientId The App's client.
 * @property accessToken The token.
 */
class GitHubRevocable(val clientId: String, val accessToken: String) {
    override fun toString(): String = "GitHubRevocable(clientId=$clientId)"
}

/**
 * A member's GitHub access for their own cloud sessions.
 */
sealed interface GitHubAccess {
    /**
     * A token to hand to the runner.
     *
     * @property token The user-to-server token.
     * @property expiresAt When it expires, null when the App does not expire user tokens.
     * @property login The linked account, for the agent's `gh` configuration.
     * @property userId Its id, for the Git identity.
     */
    class Linked(val token: String, val expiresAt: Instant?, val login: String, val userId: Long) : GitHubAccess {
        override fun toString(): String = "Linked($login, expiresAt=$expiresAt)"
    }

    /** No usable link: public repositories clone anonymously. */
    data object NotLinked : GitHubAccess

    /** GitHub could not refresh the token and none stays valid long enough: ask again later. */
    data object Unavailable : GitHubAccess
}
