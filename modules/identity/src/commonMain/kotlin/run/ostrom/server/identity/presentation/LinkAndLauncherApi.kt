// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.identity.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/**
 * `POST /api/v1/auth/{provider}/link?redirect=`: a signed-in user (browser session, CSRF token) starts linking a
 * provider to their account (S2-07). The answer gives the consent page to open; the state cookie is set as for a
 * sign-in, and the callback links the identity to that user.
 *
 * @property parent The provider resource.
 * @property redirect The console path to return to.
 */
@Serializable
@Resource("link")
class ProviderLinkApi(val parent: SignInApi.Provider, val redirect: String? = null)

/**
 * The CLI's launcher login.
 *
 * @property parent The auth resource.
 */
@Serializable
@Resource("launcher")
class LauncherApi(val parent: SignInApi = SignInApi()) {
    /**
     * `POST /api/v1/auth/launcher/login`: a GitHub token of Ostrom's GitHub app for a CLI token (S2-22).
     *
     * @property parent The launcher resource.
     */
    @Serializable
    @Resource("login")
    class Login(val parent: LauncherApi = LauncherApi())
}

/**
 * The consent page of a started link.
 *
 * @property authorizationUrl Where to send the browser.
 */
@Serializable
data class LinkStartedResponse(val authorizationUrl: String)

/**
 * Body of `POST /api/v1/auth/launcher/login`.
 *
 * @property githubToken The GitHub token the CLI obtained from Ostrom's GitHub app.
 * @property via What the CLI is (machine name), the token's label.
 */
@Serializable
data class LauncherLoginRequest(val githubToken: String, val via: String? = null)

/**
 * Answer of `POST /api/v1/auth/launcher/login`.
 *
 * @property login The Ostrom login of the user.
 * @property token The CLI token, given once.
 * @property expiresAt When it stops working (ISO 8601).
 */
@Serializable
data class LauncherLoginResponse(val login: String, val token: String, val expiresAt: String)
