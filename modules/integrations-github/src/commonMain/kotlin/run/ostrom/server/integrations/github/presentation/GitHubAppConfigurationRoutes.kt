// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.presentation

import run.ostrom.server.integrations.github.domain.GitHubAppConfiguration
import run.ostrom.server.integrations.github.domain.GitHubAppConfigurations
import run.ostrom.server.integrations.github.domain.GitHubAppInput
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requirePrincipal
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable

/** `GET` and `PUT /api/v1/github/app/configuration` (`getGithubAppConfiguration`, `saveGithubAppConfiguration`). */
@Serializable
@Resource("/api/v1/github/app/configuration")
class GitHubAppConfigurationPath

/**
 * The instance's App, without its key or secrets (the contract's `GitHubAppConfiguration`, TeamCode's `GitHubAppView`).
 *
 * @property configured Whether an App is stored.
 * @property appId Its App ID.
 * @property slug Its slug.
 * @property owner The account that owns it.
 * @property clientId Its OAuth client ID.
 * @property privateKeySet Whether its private key is stored and opens.
 * @property clientSecretSet Whether its client secret is stored and opens.
 * @property webhookSecretSet Whether its webhook secret is stored and opens.
 * @property version The stored version.
 * @property updatedAt The last save.
 * @property callbackUrls The callback URLs to register on the App.
 */
@Serializable
data class GitHubAppConfigurationResponse(
    val configured: Boolean,
    val appId: Long? = null,
    val slug: String? = null,
    val owner: String? = null,
    val clientId: String? = null,
    val privateKeySet: Boolean,
    val clientSecretSet: Boolean,
    val webhookSecretSet: Boolean,
    val version: Int? = null,
    val updatedAt: String? = null,
    val callbackUrls: List<String>,
)

/**
 * The App an administrator enters (the contract's `SaveGitHubAppConfigurationRequest`): a null secret keeps the stored
 * one.
 *
 * @property appId The App ID.
 * @property clientId Its OAuth client ID.
 * @property clientSecret Its client secret.
 * @property privateKey Its PEM private key.
 * @property webhookSecret Its webhook secret.
 * @property version The stored version, or null for a first App.
 */
@Serializable
class SaveGitHubAppConfigurationRequest(
    val appId: Long,
    val clientId: String,
    val clientSecret: String? = null,
    val privateKey: String? = null,
    val webhookSecret: String? = null,
    val version: Int? = null,
) {
    // The key and the secrets never appear in a log or a dump.
    override fun toString(): String = "SaveGitHubAppConfigurationRequest(appId=$appId, clientId=$clientId)"
}

/**
 * What the configuration routes need.
 *
 * @property configurations The use case.
 */
data class GitHubAppConfigurationRoutesDependencies(val configurations: GitHubAppConfigurations)

/**
 * The App entered by hand: an administrator of the instance signed in as a person, never a key or a token (the use
 * case checks it, as the manifest flow does).
 *
 * @param dependencies The use case.
 */
fun Route.gitHubAppConfigurationRoutes(dependencies: GitHubAppConfigurationRoutesDependencies) = with(dependencies) {
    authenticated {
        get<GitHubAppConfigurationPath> {
            call.respond(configurations.read(call.requirePrincipal()).toResponse())
        }
        put<GitHubAppConfigurationPath> {
            val principal = call.requirePrincipal()
            val request = call.receive<SaveGitHubAppConfigurationRequest>()
            call.respond(configurations.save(principal, request.toInput()).toResponse())
        }
    }
}

internal fun SaveGitHubAppConfigurationRequest.toInput() =
    GitHubAppInput(appId, clientId, clientSecret, privateKey, webhookSecret, version)

internal fun GitHubAppConfiguration.toResponse() = GitHubAppConfigurationResponse(
    configured = app != null,
    appId = app?.appId?.toLong(),
    slug = app?.slug,
    owner = app?.owner,
    clientId = app?.clientId,
    privateKeySet = app?.privateKeyPem != null,
    clientSecretSet = app?.clientSecret != null,
    webhookSecretSet = app?.webhookSecret != null,
    version = app?.version,
    updatedAt = app?.updatedAt?.toString(),
    callbackUrls = callbackUrls,
)
