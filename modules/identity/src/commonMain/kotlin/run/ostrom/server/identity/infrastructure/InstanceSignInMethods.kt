// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.OAuthProvider
import run.ostrom.server.identity.domain.SignInMethods
import run.ostrom.server.identity.domain.SignInMethodsSource
import run.ostrom.server.kernel.logging.Logger
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

/**
 * [SignInMethodsSource] on the instance's settings: GitHub and Google once an administrator set their client in the
 * console, read at each sign-in (the settings are kept 30 seconds per replica; the replica that saved sees them at
 * once).
 *
 * @param settings The sign-in settings.
 * @param http The HTTP client the providers are called with.
 * @param clock The clock, for an ID token's expiry.
 * @param logger Where a failed read of GitHub's sign-in is said: GitHub alone is left out then, every other method
 *   stays offered.
 * @param email Whether e-mailed sign-in links can be sent.
 */
class InstanceSignInMethods(
    private val settings: SignInSettings,
    private val http: HttpClient,
    private val clock: Clock,
    private val logger: Logger,
    private val email: suspend () -> Boolean,
) : SignInMethodsSource {
    override suspend fun current(): SignInMethods {
        val github = gitHub()
        val google = settings.google()
        val oauth = buildMap<IdentityProvider, OAuthProvider> {
            if (github != null) {
                put(IdentityProvider.GITHUB, GitHubOAuthProvider(github.client, http, github.webUrl, github.apiUrl))
            }
            if (google != null) {
                val endpoints = google.endpoints.withAccountPicker()
                val provider = OidcProvider(IdentityProvider.GOOGLE, endpoints, google.client, http, clock)
                put(IdentityProvider.GOOGLE, provider)
            }
        }
        return SignInMethods(oauth, email())
    }

    // GitHub's sign-in reads the stored App too (its OAuth client), in another module's table: a failed read leaves
    // GitHub out of the list rather than taking Google and the e-mailed links down with it.
    private suspend fun gitHub(): GitHubSignIn? = try {
        settings.github()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val fields = mapOf("error" to error::class.simpleName)
        logger.warn("GitHub sign-in left out: its settings could not be read", fields)
        null
    }

    /** Google's consent page lets the person pick an account instead of reusing the last one silently. */
    private fun OidcEndpoints.withAccountPicker() =
        copy(extraParameters = extraParameters + ("prompt" to "select_account"))
}
