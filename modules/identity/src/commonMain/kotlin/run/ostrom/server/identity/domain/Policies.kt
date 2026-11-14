// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.domain

import run.ostrom.server.kernel.auth.ALL_USER_SCOPES
import run.ostrom.server.kernel.auth.GovernanceScopes
import run.ostrom.server.kernel.auth.ServiceKeyScopes
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * How long a browser session lasts, whatever its use: a working day, after which the person signs in again (no sliding
 * renewal). A cookie lifted from a browser stops working the same day (TeamCode's dashboard session, TC-CODE-AUTH-3).
 */
val SESSION_LIFETIME: Duration = 8.hours

/**
 * At most this many live browser sessions per person: signing in once more ends the least recently used one, so a
 * script that signs in in a loop cannot pile sessions up (TeamCode's 50 sessions per member).
 */
const val MAX_WEB_SESSIONS_PER_USER = 50

/** A session or token's last use is written at most this often: authenticating a request stays one read. */
val SESSION_TOUCH_INTERVAL: Duration = 5.minutes

/** The longest a personal token can live; the console offers shorter. */
val MAX_TOKEN_LIFETIME: Duration = 366.days

/** How long a CLI token lasts before `ostrom login` is needed again: 30 days, as the phone app's token. */
val CLI_TOKEN_LIFETIME: Duration = 30.days

/**
 * At most this many signed-in workstations (live CLI tokens) per person: a new `ostrom login` beyond it signs the
 * oldest one out instead of being refused (TeamCode's 50 devices per member).
 */
const val MAX_DEVICES_PER_USER = 50

/** The longest an agent token lives: one agent session, never more than a day. */
val MAX_AGENT_TOKEN_LIFETIME: Duration = 24.hours

/** How long the user has to confirm an `ostrom login`. */
val DEVICE_CODE_LIFETIME: Duration = 10.minutes

/** How often the CLI may poll (RFC 8628 `interval`). */
val DEVICE_POLL_INTERVAL: Duration = 5.seconds

/** The scope every agent token holds: acting on its own agent session. */
const val AGENT_SCOPE = "agent:session"

/**
 * The scope an agent token issued since workflow deliveries holds besides [AGENT_SCOPE]: delivering the workflow step
 * its launch's cloud session works on, and asking whether it still waits (`deliverWorkflowSession`,
 * `pendingWorkflowSession`, the `ostrom-workflow` MCP server), nothing else. No other token holds it.
 */
const val WORKFLOW_SESSION_SCOPE = "workflow:session"

/** The scopes an agent token is issued with. */
val AGENT_SCOPES: Set<String> = setOf(AGENT_SCOPE, WORKFLOW_SESSION_SCOPE)

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
val NEVER_ON_A_TOKEN: Set<String> = setOf(ANSWER_SCOPE, AGENT_SCOPE, WORKFLOW_SESSION_SCOPE, ALL_USER_SCOPES)

/**
 * The scopes the CLI token gets: what `ostrom` does for its user (`ostrom receipts` and `verify` read the receipts of
 * their organization, `ostrom inbox` and `ostrom logs` read the approvals and gates, `ostrom claude` lists the
 * organization's MCP servers, `ostrom init` and `ostrom rules list` read its rules, decisions and gate policy), nothing
 * of the console's administration, and neither [ANSWER_SCOPE] nor [WORKFLOWS_WRITE_SCOPE] (S2-04): `ostrom approve`
 * opens the console's page instead. `rules:read` only reads: a rule and the gate policy are written from the console
 * (H1). A CLI token issued before a scope joined this set does not get it: `ostrom login` again.
 *
 * One write besides `sessions:write`: `cloud:write` (which reads too), for `ostrom claude --cloud` and `ostrom
 * attach`, which start, stop and attach to the person's own cloud sessions. An agent that reads this token can open
 * one, as it can already open a launch: the session is the person's, bounded by the plan (hours, sessions at once),
 * its agent launched under the organization's policy by the runner, and its clone has no credential to push with. It
 * governs nothing (decided on 03/11/2026, line 6.2 of the demo journey).
 *
 * `environment:read` reads the person's own environment values (`GET /api/v1/environment`), which `ostrom claude`
 * gives its agent, as TeamCode's `teamcode run` does: they are the person's own, as in their shell.
 */
val CLI_SCOPES: Set<String> = setOf(
    "profile:read", "orgs:read", "workflows:read", "sessions:write", "receipts:read", "gates:read", "mcp:read",
    "rules:read", "cloud:write", "environment:read", "skills:read", "skills:write",
)

/**
 * The governance write scopes ([GovernanceScopes]) only the token of `ostrom login --admin` holds: approved in the
 * browser by an owner or an admin of the organization it is bound to, for [ADMIN_TOKEN_LIFETIME] at most, never
 * renewed (decision of 02/11/2026). No personal, CLI, agent or app token ever holds them, whatever its row lists.
 */
val ADMIN_SCOPES: Set<String> = GovernanceScopes.ALL

/** How long the token of `ostrom login --admin` lives: a working day, after which `ostrom login --admin` again. */
val ADMIN_TOKEN_LIFETIME: Duration = 8.hours

/**
 * The reads the token of `ostrom login --admin` makes to write the governance, which the device's token lacks: the
 * model policy `ostrom policy set models` reads before it writes it again (it keeps the default and the members'
 * lists). Reads, not governance scopes: a personal token keeps them, and `ostrom login --admin` does not ask for them
 * (the browser shows the writes it approves). Only a read of what [ADMIN_SCOPES] write belongs here: `inference:read`
 * opens `GET /api/v1/policy/models` alone (the inference settings take `inference:admin`).
 */
val ADMIN_READ_SCOPES: Set<String> = setOf("inference:read")

/** What the token of `ostrom login --admin` grants: what the CLI does, the governance writes and their reads. */
val ADMIN_TOKEN_SCOPES: Set<String> = CLI_SCOPES + ADMIN_SCOPES + ADMIN_READ_SCOPES

/** The scope that changes the caller's notification preferences (`PUT /api/v1/me/notifications`). */
const val NOTIFICATIONS_WRITE_SCOPE = "notifications:write"

/**
 * The scopes of the phone app's token: what the app shows (the person, their organizations, the approvals and their
 * receipts, the spending, the activity feed, the waits asked of the person) and does (answer a gate or a wait, register
 * for push, set its notifications).
 * Nothing of the console's administration, no workflow write, no token management.
 */
val MOBILE_SCOPES: Set<String> = setOf(
    "profile:read", "orgs:read", "gates:read", ANSWER_SCOPE, "receipts:read", "usage:read", "activity:read",
    NOTIFICATIONS_WRITE_SCOPE, "waits:read", MOBILE_WAITS_ANSWER_SCOPE,
)

/**
 * The scope with which the app answers a wait asked of its person ("Waiting for you"). Like a yes, an answer can carry
 * what only a person gives (a decision, an approval), so the app holds it only within [MOBILE_UNLOCK_WINDOW].
 */
const val MOBILE_WAITS_ANSWER_SCOPE = "waits:write"

/** The scopes the app's token holds only within [MOBILE_UNLOCK_WINDOW] of the phone's last biometric unlock. */
val MOBILE_UNLOCKED_SCOPES: Set<String> = setOf(ANSWER_SCOPE, MOBILE_WAITS_ANSWER_SCOPE)

/**
 * How long after the phone's biometric unlock the app's token may say yes (S2-54): the step-up window of a yes. The
 * identity module withholds [ANSWER_SCOPE] outside it, so no gate route can accept a yes from a token lifted from the
 * phone's keychain without its biometrics, whatever it checks itself. A yes also takes the unlock bound to its own
 * request, at most a minute old and used once ([run.ostrom.server.kernel.auth.PhoneUnlockProofs], Q-APP-STEPUP-RISK):
 * the five minutes are a no's.
 */
val MOBILE_UNLOCK_WINDOW: Duration = 5.minutes

/**
 * The scopes the app's token grants now: [MOBILE_SCOPES] at most, and [MOBILE_UNLOCKED_SCOPES] only within
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
    return if (unlocked) granted else granted - MOBILE_UNLOCKED_SCOPES
}

/**
 * The scopes a stored token grants when it is used, checked again at each request (defense in depth: a row written
 * before S2-04, or by hand, cannot grant more): an agent token [AGENT_SCOPE] and, when its row lists it,
 * [WORKFLOW_SESSION_SCOPE] (a token issued before it existed does not deliver), a CLI token at most [CLI_SCOPES],
 * the token of `ostrom login --admin` at most [ADMIN_TOKEN_SCOPES], the app's token at most [MOBILE_SCOPES], a
 * personal token what it lists minus [NEVER_ON_A_TOKEN] and [ADMIN_SCOPES], a service key at most
 * [SERVICE_KEY_SCOPES] (none of which is a governance scope).
 *
 * @param kind The kind of the token.
 * @param stored The scopes its row lists.
 *
 * @return The scopes it grants.
 */
fun grantedScopes(kind: TokenKind, stored: Set<String>): Set<String> = when (kind) {
    TokenKind.AGENT -> setOf(AGENT_SCOPE) + (stored intersect AGENT_SCOPES)
    TokenKind.CLI -> stored intersect CLI_SCOPES
    TokenKind.ADMIN -> stored intersect ADMIN_TOKEN_SCOPES
    TokenKind.MOBILE -> stored intersect MOBILE_SCOPES
    TokenKind.PERSONAL -> stored - NEVER_ON_A_TOKEN - ADMIN_SCOPES
    TokenKind.SERVICE -> stored intersect SERVICE_KEY_SCOPES
}

/** At most this many scopes on one token. */
const val MAX_SCOPES = 32

/**
 * At most this many live personal tokens per user: a list and a guard against a runaway script. A new one beyond it is
 * refused (the person chooses which to revoke); CLI tokens have their own cap, [MAX_DEVICES_PER_USER].
 */
const val MAX_TOKENS_PER_USER = 100

/** At most this many of a user's live tokens in one listing: every personal token, workstation and phone. */
const val MAX_LISTED_TOKENS = MAX_TOKENS_PER_USER + MAX_DEVICES_PER_USER + MAX_PHONES_PER_USER

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

/** A token name is blank, longer than 100 characters, or holds a character that breaks a line or hides text. */
class InvalidTokenNameException : OstromException(
    ErrorKind.INVALID_REQUEST,
    "A token name has 1 to 100 characters, without a line break, a tab or an invisible character.",
)

/** The organization already has [MAX_SERVICE_KEYS_PER_ORG] live service keys. */
class TooManyServiceKeysException :
    OstromException(ErrorKind.CONFLICT, "Too many live service keys: delete one before creating another.")

/**
 * What a service key holds for a CI: it acts for its organization, each call audited in its name. `sessions:write`
 * reports a check (`reportCheck`) and signs an ended session's workflow receipt (`signWorkflowReceipt`); `rules:read`
 * reads the rules (`listRules`, `getRule`). No other route admits a key on these scopes alone: never a governance
 * write, a token, a member or billing.
 */
val CI_SERVICE_KEY_SCOPES: Set<String> = ServiceKeyScopes.CI

/**
 * What a service key holds to act as the person who created it, within the scope and as a plain member (the catalogue
 * of TeamCode's service keys, mapped on the routes Ostrom serves): the inference gateway, the relayed MCP servers, the
 * creator's own usage, their cloud sessions (`cloud:write` reads too), the artifacts they can read (`artifacts:write`
 * reads too, and never changes an access level nor deletes), the skills delivered to them (`skills:read`, a cloud
 * pod's or a script's). TeamCode's `hooks:*` and `guidance` scopes join the day Ostrom serves those features.
 */
val MEMBER_SERVICE_KEY_SCOPES: Set<String> = setOf(
    "inference:use", "mcp:use", "usage:read", "cloud:read", "cloud:write", "artifacts:read", "artifacts:write",
    "skills:read",
)

/**
 * The scopes reserved to an owner or an admin ([ServiceKeyScopes.RESERVED]): `team:read` (a read-only view of the
 * organization), `cloud:admin` (`cloud:write` with an administrator's rights over every cloud session), and the skill
 * catalogue's publication by a CI (`skills:publish`, with `skills:pin` and `skills:assign`). Only an
 * owner or an admin creates a key, and a key with one of these works only while its creator is still one: a demoted
 * or removed creator takes the key down at the next request, without a revocation.
 */
val RESERVED_SERVICE_KEY_SCOPES: Set<String> = ServiceKeyScopes.RESERVED

/** Every scope a service key may hold; the catalogue is [CI_SERVICE_KEY_SCOPES] or the others, never both on a key. */
val SERVICE_KEY_SCOPES: Set<String> = CI_SERVICE_KEY_SCOPES + MEMBER_SERVICE_KEY_SCOPES + RESERVED_SERVICE_KEY_SCOPES

/** A service key asks for a scope it may not hold: 400. */
class InvalidServiceKeyScopesException : OstromException(
    ErrorKind.INVALID_REQUEST,
    "A service key holds scopes of the catalogue (${SERVICE_KEY_SCOPES.joinToString(", ")}); the CI scopes " +
        "(${CI_SERVICE_KEY_SCOPES.joinToString(", ")}) are not mixed with the others.",
)

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

/** The user already has [MAX_TOKENS_PER_USER] live personal tokens. */
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
