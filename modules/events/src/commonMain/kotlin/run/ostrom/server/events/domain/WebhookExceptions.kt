// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/**
 * A webhook that breaks a rule of its shape: 400 `invalid_webhook`.
 *
 * @param message What to fix.
 */
class InvalidWebhookException(message: String) : OstromException(ErrorKind.INVALID_REQUEST, message, "invalid_webhook")

/** A target off the public internet: 400 `webhook_host_not_allowed`. */
class WebhookHostNotAllowedException : OstromException(
    ErrorKind.INVALID_REQUEST,
    "Webhooks reach public addresses only: not localhost, a loopback, private or reserved address. The server " +
        "operator can allow private targets with OSTROM_WEBHOOKS_ALLOW_PRIVATE_TARGETS=true.",
    "webhook_host_not_allowed",
)

/** Past the webhooks of an organization: 409 `webhook_limit`. */
class WebhookLimitException : OstromException(
    ErrorKind.CONFLICT,
    "An organization has at most ${WebhookRules.MAX_PER_ORG} webhooks.",
    "webhook_limit",
)

/** An update of a version that is no longer current: 409 `stale_version`. */
class StaleWebhookVersionException : OstromException(
    ErrorKind.CONFLICT,
    "The webhook changed. Reload it and try again.",
    "stale_version",
)

/** A webhook that does not exist in the organization: 404. */
class WebhookNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Webhook not found.")

/** Too many tests of one webhook: 429 `webhook_test_rate`, `Retry-After: 10`. */
class WebhookTestRateException : OstromException(
    ErrorKind.RATE_LIMITED,
    "Wait a few seconds between tests.",
    "webhook_test_rate",
) {
    /** The `Retry-After` of the answer. */
    val retryAfterSeconds: Long = WebhookRules.TEST_WINDOW.inWholeSeconds
}

/** A credential that may not manage the organization's webhooks: 403. */
class WebhooksForbiddenException : OstromException(
    ErrorKind.FORBIDDEN,
    "Only an owner or an admin manages webhooks, with a browser session, a token of theirs holding cloud:read " +
        "(cloud:write to change them), or a service key of theirs holding cloud:admin.",
)
