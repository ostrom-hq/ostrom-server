// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules.domain.models

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/** The rule does not exist, or belongs to another organization (the same answer, so nothing leaks). */
class RuleNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Rule not found.")

/**
 * A rule, a decision or a policy sent by the console is malformed.
 *
 * @param reason What is wrong, safe to show.
 */
class InvalidRuleException(reason: String) : OstromException(ErrorKind.INVALID_REQUEST, reason)

/** Only an owner or admin, in the console or with `ostrom login --admin`, decides a rule or a policy (S2-04, S2-52). */
class OnlyConsoleDecidesException : OstromException(
    ErrorKind.FORBIDDEN,
    "Only an owner or admin, in the console or with ostrom login --admin, writes or decides rules and policies.",
)
