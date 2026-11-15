// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets

import run.ostrom.server.sharing.domain.AccountLimits
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.AccountStatus
import run.ostrom.server.sharing.domain.PoolAccountObservation
import run.ostrom.server.sharing.domain.QuotaWindow
import run.ostrom.server.sharing.domain.RefreshOwnership
import run.ostrom.server.sharing.domain.SharedAccount
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A fixed id per name, so that the pool size tests read as TeamCode's (`"plus"`, `"max"`...). */
internal fun accountId(name: String): Uuid = Uuid.fromLongs(name.hashCode().toLong(), name.length.toLong())

/** A shared account named [name], ready unless said otherwise, at TeamCode's .95 pressure threshold. */
@Suppress("LongParameterList") // Each test names only what it changes.
internal fun sharedAccount(
    name: String,
    now: Instant,
    status: AccountStatus = AccountStatus.READY,
    ownership: RefreshOwnership = RefreshOwnership.OSTROM,
    cooldownUntil: Instant? = null,
    ceiling: Double = .95,
    orgId: Uuid = Uuid.random(),
    priority: Int = 0,
) = SharedAccount(
    id = accountId(name),
    orgId = orgId,
    provider = AccountProvider.CLAUDE,
    label = name,
    email = null,
    providerAccountId = name,
    ownerMemberId = null,
    priority = priority,
    status = status,
    limits = AccountLimits(utilizationCeiling = ceiling),
    cooldownUntil = cooldownUntil,
    createdAt = now,
    ownership = ownership,
)

/** A reading of [window] at [utilization], observed [observedAgo] before [now], resetting [resetsIn] after it. */
internal fun quota(
    window: String,
    utilization: Double,
    resetsIn: Duration,
    now: Instant,
    observedAgo: Duration = Duration.ZERO,
) = QuotaWindow(window, utilization, now + resetsIn, now - observedAgo)

/** [account] and its readings. */
internal fun observed(account: SharedAccount, vararg windows: QuotaWindow) =
    PoolAccountObservation(account, windows.toList())
