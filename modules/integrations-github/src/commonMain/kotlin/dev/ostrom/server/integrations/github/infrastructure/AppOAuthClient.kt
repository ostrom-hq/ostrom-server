// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github.infrastructure

/**
 * The GitHub App's OAuth client, which exchanges the code of the install's return for the person's token.
 *
 * @property id The client id.
 * @property secret The client secret: never in a log nor a dump.
 */
class AppOAuthClient(val id: String, val secret: String) {
    override fun toString(): String = "AppOAuthClient(id=$id)"
}
