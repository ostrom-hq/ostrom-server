// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.http

import run.ostrom.server.kernel.logging.Logger

/**
 * Configuration of [RequestLogging].
 *
 * @property logger Where request lines go; set by [configureHttp].
 */
class RequestLoggingConfig {
    lateinit var logger: Logger
}
