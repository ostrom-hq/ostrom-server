// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/** `GET`/`DELETE /api/v1/github/link` (`getGithubLink`, `unlinkGithub`): the caller's own GitHub link. */
@Serializable
@Resource("/api/v1/github/link")
class GitHubLinkPath {
    /**
     * `POST /api/v1/github/link/device` (`startGithubDeviceLink`).
     *
     * @property parent The link.
     */
    @Serializable
    @Resource("device")
    class Device(val parent: GitHubLinkPath = GitHubLinkPath()) {
        /**
         * `POST /api/v1/github/link/device/{id}` (`pollGithubDeviceLink`).
         *
         * @property parent The device flow.
         * @property id The attempt.
         */
        @Serializable
        @Resource("{id}")
        class Id(val parent: Device = Device(), val id: String)
    }

    /**
     * `GET /api/v1/github/link/start` (`startGithubWebLink`): the console's web flow.
     *
     * @property parent The link.
     */
    @Serializable
    @Resource("start")
    class Start(val parent: GitHubLinkPath = GitHubLinkPath())

    /**
     * `GET /api/v1/github/link/callback` (`githubLinkCallback`): GitHub's return.
     *
     * @property parent The link.
     * @property code GitHub's code.
     * @property state The state of the start.
     * @property error GitHub's error, when the member cancelled.
     */
    @Serializable
    @Resource("callback")
    class Callback(
        val parent: GitHubLinkPath = GitHubLinkPath(),
        val code: String? = null,
        val state: String? = null,
        val error: String? = null,
    )
}

/** `GET /api/v1/github/links` (`listGithubLinks`): every member's link, for owners and admins. */
@Serializable
@Resource("/api/v1/github/links")
class GitHubLinksPath {
    /**
     * `GET`/`DELETE /api/v1/github/links/{memberId}` (`getMemberGithubLink`, `unlinkMemberGithub`).
     *
     * @property parent The links.
     * @property memberId The member.
     */
    @Serializable
    @Resource("{memberId}")
    class Member(val parent: GitHubLinksPath = GitHubLinksPath(), val memberId: String) {
        /**
         * `GET /api/v1/github/links/{memberId}/start` (`startMemberGithubWebLink`).
         *
         * @property parent The member's link.
         */
        @Serializable
        @Resource("start")
        class Start(val parent: Member)
    }
}

/**
 * A member's link, as the contract's `GitHubLink`; never a token.
 *
 * @property memberId The member.
 * @property state `not_linked`, `linked` or `reconnect_required`.
 * @property appConfigured Whether the instance's App can link accounts.
 * @property login The GitHub account.
 * @property userId Its id.
 * @property linkedAt When it was linked.
 * @property refreshedAt When its tokens were last renewed.
 * @property accessExpiresAt When its access token expires.
 */
@Serializable
data class GitHubLinkDto(
    val memberId: String,
    val state: String,
    val appConfigured: Boolean,
    val login: String? = null,
    val userId: Long? = null,
    val linkedAt: String? = null,
    val refreshedAt: String? = null,
    val accessExpiresAt: String? = null,
)

/**
 * The organization's links.
 *
 * @property items The links, by GitHub login.
 */
@Serializable
data class GitHubLinksPage(val items: List<GitHubLinkDto>)

/**
 * A device link just started, as the contract's `GitHubDeviceLink`.
 *
 * @property id The attempt to poll.
 * @property userCode The code to enter on GitHub.
 * @property verificationUri Where to enter it.
 * @property expiresAt When it ends.
 * @property interval Seconds between two polls.
 */
@Serializable
data class GitHubDeviceLinkDto(
    val id: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAt: String,
    val interval: Int,
)

/**
 * One poll, as the contract's `GitHubDeviceLinkPoll`.
 *
 * @property status `pending`, `linked`, `expired` or `denied`.
 * @property interval Seconds before the next poll, while pending.
 * @property link The link, once linked.
 */
@Serializable
data class GitHubDeviceLinkPollDto(val status: String, val interval: Int? = null, val link: GitHubLinkDto? = null)
