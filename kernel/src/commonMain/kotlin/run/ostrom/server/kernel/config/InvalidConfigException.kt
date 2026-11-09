// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.config

/**
 * A configuration value is missing or invalid; the server refuses to start rather than guess.
 *
 * @param cause The parser's own error, when there is one (its message names the variable, never its value).
 */
class InvalidConfigException(message: String, cause: Throwable? = null) : Exception(message, cause)
