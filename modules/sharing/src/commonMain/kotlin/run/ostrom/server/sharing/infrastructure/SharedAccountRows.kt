// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.infrastructure

import run.ostrom.server.sharing.domain.AccountLimits
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.AccountStatus
import run.ostrom.server.sharing.domain.RefreshOwnership
import run.ostrom.server.sharing.domain.SharedAccount
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import kotlin.uuid.Uuid

/** The columns of an account row, tokens excluded: the envelope is only read by its own query. */
internal val ACCOUNT_COLUMNS = SharingAccounts.run {
    listOf(
        id, orgId, provider, label, email, providerAccountId, ownerMemberId, priority, status, maxConcurrent,
        utilizationCeiling, monthlyBudgetMicros, allowedMembers, cooldownUntil, createdAt, version, dailyBudgetMicros,
        ownership,
    )
}

/** The statuses only sharing again leaves ([AccountStatus.awaitsSignIn]), kept by a stale write. */
internal val AWAITING_SIGN_IN: List<String> = AccountStatus.entries.filter { it.awaitsSignIn }.map { it.name }

private val MEMBERS = SetSerializer(String.serializer())

/** Writes [limits]: the concurrency, the ceiling, the budgets and the allowed members (a JSON array, or NULL). */
internal fun UpdateBuilder<*>.limits(limits: AccountLimits) {
    val a = SharingAccounts
    val allowed = limits.allowedMembers
    this[a.maxConcurrent] = limits.maxConcurrent
    this[a.utilizationCeiling] = limits.utilizationCeiling
    this[a.monthlyBudgetMicros] = limits.monthlyBudgetMicros
    this[a.dailyBudgetMicros] = limits.dailyBudgetMicros
    this[a.allowedMembers] = if (allowed == null) {
        null
    } else {
        Json.encodeToString(MEMBERS, allowed.map { it.toString() }.toSet())
    }
}

/** Reads an account row of [ACCOUNT_COLUMNS]. */
internal fun ResultRow.toAccount(): SharedAccount {
    val a = SharingAccounts
    val allowed = this[a.allowedMembers]
    return SharedAccount(
        id = this[a.id],
        orgId = this[a.orgId],
        provider = AccountProvider.valueOf(this[a.provider]),
        label = this[a.label],
        email = this[a.email],
        providerAccountId = this[a.providerAccountId],
        ownerMemberId = this[a.ownerMemberId],
        priority = this[a.priority],
        status = AccountStatus.valueOf(this[a.status]),
        limits = AccountLimits(
            maxConcurrent = this[a.maxConcurrent],
            utilizationCeiling = this[a.utilizationCeiling],
            monthlyBudgetMicros = this[a.monthlyBudgetMicros],
            allowedMembers = if (allowed == null) {
                null
            } else {
                Json.decodeFromString(MEMBERS, allowed).map(Uuid::parse).toSet()
            },
            dailyBudgetMicros = this[a.dailyBudgetMicros],
        ),
        cooldownUntil = this[a.cooldownUntil],
        createdAt = this[a.createdAt],
        version = this[a.version],
        ownership = RefreshOwnership.valueOf(this[a.ownership]),
    )
}
