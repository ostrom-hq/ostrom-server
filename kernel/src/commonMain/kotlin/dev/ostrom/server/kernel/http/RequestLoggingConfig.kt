// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.http

import dev.ostrom.server.kernel.logging.Logger

/**
 * Configuration of [RequestLogging].
 *
 * @property logger Where request lines go; set by [configureHttp].
 */
class RequestLoggingConfig {
    lateinit var logger: Logger
}
