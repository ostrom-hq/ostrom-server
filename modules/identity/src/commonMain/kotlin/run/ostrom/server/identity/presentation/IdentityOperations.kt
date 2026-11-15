// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.presentation

import run.ostrom.server.kernel.http.docs.ApiCatalog

/**
 * The identity module's operations: signing in and out, the person's account, tokens and workstations, the
 * organization's service keys and audit log. Its `auth` tag is declared with the launches', its `notifications` tag
 * with the notifications module's.
 */
internal fun ApiCatalog.identityOperations() {
    signInOperations()
    accountOperations()
    keysAndDevicesOperations()
}
