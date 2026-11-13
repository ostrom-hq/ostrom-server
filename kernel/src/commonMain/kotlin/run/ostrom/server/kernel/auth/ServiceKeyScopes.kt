// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.auth

/**
 * The scopes only a service key holds, and the split of its catalogue that the modules read (the identity module owns
 * the whole catalogue, `SERVICE_KEY_SCOPES`).
 *
 * A service key holds either the CI scopes (it acts for its organization, each call audited) or member scopes (it acts
 * as the person who created it, within those scopes, and never with an administrator's rights unless a route says so).
 * The two are never mixed on one key.
 */
object ServiceKeyScopes {
    /**
     * Reads the organization as an administrator sees it, without a write: usage, spending, budgets, accounts, MCP
     * usage and the member directory. Never a session, a transcript or an artifact of a member.
     */
    const val TEAM_READ: String = "team:read"

    /** `cloud:write` with an administrator's rights over every cloud session of the organization. */
    const val CLOUD_ADMIN: String = "cloud:admin"

    /**
     * The scopes reserved to an owner or an admin: only they create a key with one, and the key stops at once when its
     * creator is no longer one.
     */
    val RESERVED: Set<String> = setOf(TEAM_READ, CLOUD_ADMIN)

    /** What a CI calls: the key acts for its organization, audited, and never as a person. */
    val CI: Set<String> = setOf("sessions:write", "rules:read")
}
