// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.domain

import dev.ostrom.server.kernel.auth.ALL_USER_SCOPES
import dev.ostrom.server.kernel.auth.GovernanceScopes
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** How long a browser session lasts, whatever its use: the user signs in again after that. */
val SESSION_LIFETIME: Duration = 30.days

/** A session or token's last use is written at most this often: authenticating a request stays one read. */
val SESSION_TOUCH_INTERVAL: Duration = 5.minutes

/** The longest a personal token can live; the console offers shorter. */
val MAX_TOKEN_LIFETIME: Duration = 366.days

/** How long a CLI token lasts before `ostrom login` is needed again. */
val CLI_TOKEN_LIFETIME: Duration = 90.days

/** The longest an agent token lives: one agent session, never more than a day. */
val MAX_AGENT_TOKEN_LIFETIME: Duration = 24.hours

/** How long the user has to confirm an `ostrom login`. */
val DEVICE_CODE_LIFETIME: Duration = 10.minutes

/** How often the CLI may poll (RFC 8628 `interval`). */
val DEVICE_POLL_INTERVAL: Duration = 5.seconds

/** The only scope of an agent token: acting on its own agent session. */
const val AGENT_SCOPE = "agent:session"

/**
 * The scope that answers a gate. No token the person can create or the CLI holds has it (audit S2-04, S2-31): such a
 * token lives on the developer's machine, where the agent can read it and say yes to itself. A yes comes from a
 * browser session (the console) or the phone app's token ([MOBILE_SCOPES]), which lives in the phone's keychain and
 * says yes only after the phone's biometric unlock (S2-54); never from the CLI.
 */
const val ANSWER_SCOPE = "gates:answer"

/** The scope that edits workflows: the CLI never gets it (S2-04), so an agent cannot remove its own gate. */
const val WORKFLOWS_WRITE_SCOPE = "workflows:write"

/** Scopes no token holds, whatever was asked or a stored row says: answering, an agent's, a browser session's. */
val NEVER_ON_A_TOKEN: Set<String> = setOf(ANSWER_SCOPE, AGENT_SCOPE, ALL_USER_SCOPES)

/**
 * The scopes the CLI token gets: what `ostrom` does for its user (`ostrom receipts` and `verify` read the receipts of
 * their organization, `ostrom inbox` and `ostrom logs` read the approvals and gates, `ostrom claude` lists the
 * organization's MCP servers, `ostrom init` and `ostrom rules list` read its rules, decisions and gate policy), nothing
 * of the console's administration, and neither [ANSWER_SCOPE] nor [WORKFLOWS_WRITE_SCOPE] (S2-04): `ostrom approve`
 * opens the console's page instead. `rules:read` only reads: a rule and the gate policy are written from the console
 * (H1). A CLI token issued before a scope joined this set does not get it: `ostrom login` again.
 */
val CLI_SCOPES: Set<String> = setOf(
    "profile:read", "orgs:read", "workflows:read", "sessions:write", "receipts:read", "gates:read", "mcp:read",
    "rules:read",
)

/**
 * The governance write scopes ([GovernanceScopes]) only the token of `ostrom login --admin` holds: approved in the
 * browser by an owner or an admin of the organization it is bound to, for [ADMIN_TOKEN_LIFETIME] at most, never
 * renewed (decision of 02/11/2026). No personal, CLI, agent or app token ever holds them, whatever its row lists.
 */
val ADMIN_SCOPES: Set<String> = GovernanceScopes.ALL

/** How long the token of `ostrom login --admin` lives: a working day, after which `ostrom login --admin` again. */
val ADMIN_TOKEN_LIFETIME: Duration = 8.hours

/** What the token of `ostrom login --admin` grants: what the CLI does, plus the governance writes. */
val ADMIN_TOKEN_SCOPES: Set<String> = CLI_SCOPES + ADMIN_SCOPES

/** The scope that changes the caller's notification preferences (`PUT /api/v1/me/notifications`). */
const val NOTIFICATIONS_WRITE_SCOPE = "notifications:write"

/**
 * The scopes of the phone app's token: what the app shows (the person, their organizations, the approvals and their
 * receipts, the usage) and does (answer a gate, register for push, set its notifications). Nothing of the console's
 * administration, no workflow write, no token management.
 */
val MOBILE_SCOPES: Set<String> = setOf(
    "profile:read", "orgs:read", "gates:read", ANSWER_SCOPE, "receipts:read", "usage:read", NOTIFICATIONS_WRITE_SCOPE,
)

/**
 * How long after the phone's biometric unlock the app's token may say yes (S2-54): the step-up window of a yes. The
 * identity module withholds [ANSWER_SCOPE] outside it, so no gate route can accept a yes from a token lifted from the
 * phone's keychain without its biometrics, whatever it checks itself.
 */
val MOBILE_UNLOCK_WINDOW: Duration = 5.minutes

/**
 * The scopes the app's token grants now: [MOBILE_SCOPES] at most, and [ANSWER_SCOPE] only within
 * [MOBILE_UNLOCK_WINDOW] of the phone's last biometric unlock.
 *
 * @param stored The app's token.
 * @param now The current time.
 *
 * @return The scopes.
 */
fun mobileScopes(stored: ApiToken, now: Instant): Set<String> {
    val granted = grantedScopes(TokenKind.MOBILE, stored.scopes)
    val unlocked = stored.authenticatedAt?.let { now - it <= MOBILE_UNLOCK_WINDOW } == true
    return if (unlocked) granted else granted - ANSWER_SCOPE
}

/**
 * The scopes a stored token grants when it is used, checked again at each request (defense in depth: a row written
 * before S2-04, or by hand, cannot grant more): an agent token only [AGENT_SCOPE], a CLI token at most [CLI_SCOPES],
 * the token of `ostrom login --admin` at most [ADMIN_TOKEN_SCOPES], the app's token at most [MOBILE_SCOPES], a
 * personal token or a service key what it lists minus [NEVER_ON_A_TOKEN] and [ADMIN_SCOPES].
 *
 * @param kind The kind of the token.
 * @param stored The scopes its row lists.
 *
 * @return The scopes it grants.
 */
fun grantedScopes(kind: TokenKind, stored: Set<String>): Set<String> = when (kind) {
    TokenKind.AGENT -> setOf(AGENT_SCOPE)
    TokenKind.CLI -> stored intersect CLI_SCOPES
    TokenKind.ADMIN -> stored intersect ADMIN_TOKEN_SCOPES
    TokenKind.MOBILE -> stored intersect MOBILE_SCOPES
    TokenKind.PERSONAL, TokenKind.SERVICE -> stored - NEVER_ON_A_TOKEN - ADMIN_SCOPES
}

/** At most this many scopes on one token. */
const val MAX_SCOPES = 32

/** At most this many live tokens per user: a list and a guard against a runaway script. */
const val MAX_TOKENS_PER_USER = 100

/** At most this many live service keys per organization, for the same reasons. */
const val MAX_SERVICE_KEYS_PER_ORG = 100

/**
 * Checks the scopes a user asks for a personal token: at least one, at most [MAX_SCOPES], each from the catalogue
 * [PERSONAL_TOKEN_SCOPES], which never lists [NEVER_ON_A_TOKEN] (an agent's scope, or [ANSWER_SCOPE]: a token never
 * says yes, S2-04) nor [ADMIN_SCOPES], which only `ostrom login --admin` gets.
 *
 * @param scopes The requested scopes.
 *
 * @return The scopes, deduplicated.
 *
 * @throws InvalidScopesException When the set is empty, too large, or names a scope outside the catalogue.
 */
fun validateScopes(scopes: Collection<String>): Set<String> {
    val set = scopes.toSet()
    if (set.isEmpty() || set.size > MAX_SCOPES) throw InvalidScopesException()
    val unknown = set - PERSONAL_TOKEN_SCOPE_NAMES
    if (unknown.isNotEmpty()) throw InvalidScopesException(unknown)
    return set
}

/**
 * Normalizes an e-mail address for comparison and storage: trimmed and lower case. Rejects what is not an address
 * (one `@`, something on both sides, a dot in the domain, no spaces, at most 254 characters).
 *
 * @param raw The address as typed or as a provider gives it.
 *
 * @return The normalized address.
 *
 * @throws InvalidEmailException When it is not an address.
 */
fun normalizeEmail(raw: String): String {
    val email = raw.trim().lowercase()
    val at = email.indexOf('@')
    val valid = email.length <= MAX_EMAIL_LENGTH && at > 0 && at == email.lastIndexOf('@') && at < email.length - 1 &&
        email.substring(at + 1).let { domain -> '.' in domain && !domain.startsWith('.') && !domain.endsWith('.') } &&
        email.none { it.isWhitespace() }
    if (!valid) throw InvalidEmailException()
    return email
}

/** The requested scopes are empty, too many, malformed, or reserved. */
class InvalidScopesException(unknown: Collection<String> = emptyList()) :
    OstromException(
        ErrorKind.INVALID_REQUEST,
        "Scopes must be 1 to $MAX_SCOPES values from the catalogue of GET /api/v1/tokens/scopes." +
            if (unknown.isEmpty()) "" else " Not in it: ${unknown.take(MAX_NAMED_UNKNOWN).joinToString(", ")}.",
    )

/** How many unknown scopes a refusal names, at most. */
private const val MAX_NAMED_UNKNOWN = 5

/** The value is not an e-mail address. */
class InvalidEmailException : OstromException(ErrorKind.INVALID_REQUEST, "This is not a valid e-mail address.")

/** A token name is blank or longer than 100 characters. */
class InvalidTokenNameException : OstromException(ErrorKind.INVALID_REQUEST, "A token name has 1 to 100 characters.")

/** The organization already has [MAX_SERVICE_KEYS_PER_ORG] live service keys. */
class TooManyServiceKeysException :
    OstromException(ErrorKind.CONFLICT, "Too many live service keys: delete one before creating another.")

/** The service key is absent, another organization's, or already deleted. */
class ServiceKeyNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Service key not found.")

/** Only an owner or an admin of the organization manages its service keys. */
class ServiceKeysAdminRequiredException :
    OstromException(ErrorKind.FORBIDDEN, "Only an owner or an admin manages the organization's service keys.")

/** The device is absent, another person's, or already signed out. */
class DeviceNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Device not found.")

/** The token lifetime is not between one day and [MAX_TOKEN_LIFETIME]. */
class InvalidTokenLifetimeException :
    OstromException(ErrorKind.INVALID_REQUEST, "A token lives between 1 and 366 days.")

/** The user already has [MAX_TOKENS_PER_USER] live tokens. */
class TooManyTokensException :
    OstromException(ErrorKind.CONFLICT, "Too many live tokens: revoke one before creating another.")

/** The token does not exist, is not the caller's, or is already revoked: the same answer, to reveal nothing. */
class TokenNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Token not found.")

/** The user does not exist (deleted while a credential was in flight). */
class UserNotFoundException : OstromException(ErrorKind.NOT_FOUND, "User not found.")

/** Too many attempts from the same source: wait and retry. */
class RateLimitedException : OstromException(ErrorKind.RATE_LIMITED, "Too many attempts. Wait a moment and retry.")

/** `ostrom login --admin` asks for the governance write scopes: only an owner or an admin approves them. */
class AdminApprovalForbiddenException : OstromException(
    ErrorKind.FORBIDDEN,
    "Only an owner or an admin of the organization approves ostrom login --admin.",
)

/** The browser session does not exist, is not the caller's, or has ended: the same answer, to reveal nothing. */
class WebSessionNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Session not found.")

/** The device code or user code is unknown, expired or already used. */
class DeviceCodeNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Unknown or expired code.")

/** The action needs a browser session of the user, not a token. */
class SessionRequiredException :
    OstromException(ErrorKind.FORBIDDEN, "This action needs a signed-in browser session.")

/**
 * A free-text value as stored: trimmed and cut to [max] characters.
 *
 * @param raw The value, if any.
 * @param max The longest kept.
 *
 * @return The value, or null when it is missing or blank.
 */
fun trimmedOrNull(raw: String?, max: Int): String? {
    if (raw == null) return null
    val trimmed = raw.trim().take(max)
    return if (trimmed.isEmpty()) null else trimmed
}

/** The longest address SMTP can carry (RFC 5321 § 4.5.3.1.3, the path minus its brackets). */
private const val MAX_EMAIL_LENGTH = 254
