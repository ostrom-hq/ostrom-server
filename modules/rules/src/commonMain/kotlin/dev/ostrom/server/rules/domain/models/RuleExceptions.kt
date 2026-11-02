// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.domain.models

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException

/** The rule does not exist, or belongs to another organization (the same answer, so nothing leaks). */
class RuleNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Rule not found.")

/**
 * A rule, a decision or a policy sent by the console is malformed.
 *
 * @param reason What is wrong, safe to show.
 */
class InvalidRuleException(reason: String) : OstromException(ErrorKind.INVALID_REQUEST, reason)

/** Only a person signed in to the console decides a rule or a policy (CONVENTIONS 16.2, SECURITY-AUDIT S2-04). */
class OnlyConsoleDecidesException : OstromException(
    ErrorKind.FORBIDDEN,
    "Only a person signed in to the console writes or decides rules and policies.",
)
