// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer.presentation

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/** A malformed import request. */
class InvalidImportException(message: String) : OstromException(ErrorKind.INVALID_REQUEST, message)
