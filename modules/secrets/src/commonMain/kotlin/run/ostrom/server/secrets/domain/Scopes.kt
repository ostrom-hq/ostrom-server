// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.secrets.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/**
 * The scopes the RELAY modules' routes require (SECURITY-AUDIT S2-03). A user's browser session ([ALL_USER]) holds
 * them all; a token holds only those listed; an agent's token holds [AGENT_SESSION], which only the routes agents use
 * accept (the MCP relay, the inference gateway, the servers a member reaches).
 */
object RelayScopes {
    /** A user's browser session: every right of the user (granted by identity to session cookies only). */
    const val ALL_USER: String = "user:*"

    /** One agent session's token. */
    const val AGENT_SESSION: String = "agent:session"

    /** Lists the secrets. */
    const val SECRETS_READ: String = "secrets:read"

    /** Saves and deletes secrets. */
    const val SECRETS_WRITE: String = "secrets:write"

    /** Reads the MCP servers, their journal and usage. */
    const val MCP_READ: String = "mcp:read"

    /** Changes the MCP servers, assignments, policies and settings. */
    const val MCP_ADMIN: String = "mcp:admin"

    /** Calls the relayed MCP servers with a personal token. */
    const val MCP_USE: String = "mcp:use"

    /** Reads the shared accounts and the capacity. */
    const val ACCOUNTS_READ: String = "accounts:read"

    /** Shares, changes and removes accounts. */
    const val ACCOUNTS_WRITE: String = "accounts:write"

    /** Reads the model policy and the inference settings. */
    const val INFERENCE_READ: String = "inference:read"

    /** Changes them. */
    const val INFERENCE_ADMIN: String = "inference:admin"

    /** Reads the usage ledger; also opens the MCP usage, as TeamCode's `usage:read` does. */
    const val USAGE_READ: String = "usage:read"

    /** Calls the inference gateway with a personal token. */
    const val INFERENCE_USE: String = "inference:use"
}

/** The credential does not carry the scope the route requires. */
class ScopeRequiredException(scope: String) :
    OstromException(ErrorKind.FORBIDDEN, "This credential does not allow $scope.")
