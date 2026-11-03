// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sharing.domain

import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.SecretNotFoundException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The contract's enrollment (`enrollSharedAccount`), as the console sends it: the subscription's token is never
 * typed there, it is a secret already stored, named here and read on the server.
 *
 * @property provider The subscription.
 * @property label The name admins see.
 * @property credentialSecret The name of the secret holding the token: the caller's own secret, or an org secret for
 *   an admin.
 * @property sharedWith The members it is lent to, by login; empty for the whole org.
 * @property dailyBudgetMicros What it may serve per UTC day (USD millionths), or null.
 */
data class EnrollAccountPayload(
    val provider: AccountProvider,
    val label: String,
    val credentialSecret: String,
    val sharedWith: List<String> = emptyList(),
    val dailyBudgetMicros: Long? = null,
)

/**
 * A secret's value, read to be sealed into an account; [toString] never shows it.
 *
 * @property id The secret.
 * @property value The plaintext.
 */
class SecretCredential(val id: Uuid, val value: String) {
    override fun toString(): String = "SecretCredential(id=$id, value=[REDACTED])"
}

/** Reads the secret an account is enrolled from. */
fun interface CredentialSecrets {
    /**
     * The secret named [name] that [caller] may lend: its own personal secret first, else, for an admin, the org's.
     *
     * @return The secret, or null when the caller has none of that name it may lend.
     */
    suspend fun read(caller: Caller, name: String): SecretCredential?
}

/** Enrolls a subscription from a secret. */
interface EnrollSharedAccountUseCase {
    /** Enrolls it; returns the stored account. */
    suspend operator fun invoke(caller: Caller, payload: EnrollAccountPayload): SharedAccount
}

/**
 * [EnrollSharedAccountUseCase]: shares the account through [share], with the secret's token (spaces and line breaks
 * around it trimmed) as a long-lived token, never renewed, of unknown expiry. The secret names the account: enrolling
 * the same secret again replaces its token, and the form's sharing (`sharedWith`, daily allocation) replaces the one
 * sharing again kept, through [update] (admins and its owner only).
 *
 * @param secrets Reads the secret.
 * @param names Reads the members it is lent to.
 * @param share Shares it.
 * @param update Applies the form's sharing to an account enrolled again.
 */
class EnrollSharedAccountUseCaseImpl(
    private val secrets: CredentialSecrets,
    private val names: MemberNames,
    private val share: ShareAccountUseCase,
    private val update: UpdateSharedAccountUseCase,
) : EnrollSharedAccountUseCase {
    override suspend fun invoke(caller: Caller, payload: EnrollAccountPayload): SharedAccount {
        val members = names.ids(caller.orgId, payload.sharedWith)
        val secret = secrets.read(caller, payload.credentialSecret) ?: throw SecretNotFoundException()
        val daily = payload.dailyBudgetMicros
        val account = share(
            caller,
            ShareAccountPayload(
                provider = payload.provider,
                label = payload.label,
                email = null,
                providerAccountId = "secret:${secret.id}",
                accessToken = secret.value.trim(),
                refreshToken = "",
                expiresAt = Instant.DISTANT_FUTURE,
                limits = AccountLimits(dailyBudgetMicros = daily, allowedMembers = members),
            ),
        )
        val kept = account.limits
        if (kept.allowedMembers == members && kept.dailyBudgetMicros == daily) return account
        val sharing = UpdateAccountPayload(
            limits = { it.copy(allowedMembers = members, dailyBudgetMicros = daily) },
            ifVersion = account.version,
        )
        return update(caller, account.id, sharing)
    }
}
