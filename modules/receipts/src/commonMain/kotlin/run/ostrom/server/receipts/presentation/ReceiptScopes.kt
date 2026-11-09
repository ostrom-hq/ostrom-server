// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.receipts.presentation

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/** Who reads the receipts of an organization (SECURITY-AUDIT S2-47): they show commands, approvers and answers. */
object ReceiptScopes {
    /** A person's session: every scope of a user. */
    const val ALL_USER: String = "user:*"

    /** A person's token that only reads receipts (an auditor, the CLI's `receipts verify`). */
    const val READ: String = "receipts:read"

    /** The scopes that open the receipts routes. */
    val reading: Set<String> = setOf(ALL_USER, READ)
}

/**
 * Refuses a credential that is not a person's, or holds no receipts scope: an agent token or a device never reads
 * the organization's receipts, whatever its scopes.
 *
 * @throws OstromException A 403.
 */
fun AuthenticatedPrincipal.requireReceiptReader() {
    if (kind != PrincipalKind.USER) {
        throw OstromException(ErrorKind.FORBIDDEN, "Receipts are read by a person, not by an agent or a device.")
    }
    if (scopes.none { it in ReceiptScopes.reading }) {
        throw OstromException(ErrorKind.FORBIDDEN, "This credential does not allow ${ReceiptScopes.READ}.")
    }
}
