// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.presentation

import run.ostrom.server.sharing.domain.AccountStatus
import run.ostrom.server.sharing.domain.RefreshOwnership

/**
 * An account's status in the API: `pending_handover` or `access_only` in place of `ready` while Ostrom does not renew
 * its tokens, the status in lower case otherwise.
 */
internal fun wireStatus(status: AccountStatus, ownership: RefreshOwnership): String =
    if (status == AccountStatus.READY && ownership != RefreshOwnership.OSTROM) {
        ownership.name.lowercase()
    } else {
        status.name.lowercase()
    }
