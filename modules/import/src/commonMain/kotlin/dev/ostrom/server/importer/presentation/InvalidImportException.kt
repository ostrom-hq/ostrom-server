// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer.presentation

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException

/** A malformed import request. */
class InvalidImportException(message: String) : OstromException(ErrorKind.INVALID_REQUEST, message)
