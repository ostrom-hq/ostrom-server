// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.auth

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/** A console write made with a credential the route does not admit, such as a token (403, SECURITY-AUDIT S2-52). */
class BrowserSessionRequiredException : OstromException(
    ErrorKind.FORBIDDEN,
    "This change needs a signed-in session in the console: a token cannot make it.",
)
