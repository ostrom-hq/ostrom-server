// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.secrets.domain

import kotlin.uuid.Uuid

/**
 * Whether a stored environment value equals a given one: a trusted server-side port, bound to no route, for an import
 * that leaves an unchanged value alone (a second run changes nothing). It never returns the stored value.
 */
fun interface EnvironmentValueCheck {
    /**
     * Compares them.
     *
     * @param orgId The organization.
     * @param variantId The variant.
     * @param memberId The member whose own value of a per-member variant is compared; null for the variant's value.
     * @param value The value to compare with.
     *
     * @return True when the stored value opens and equals [value]; false when it differs, is missing or does not open.
     */
    suspend fun matches(orgId: Uuid, variantId: Uuid, memberId: Uuid?, value: SecretValue): Boolean
}

/**
 * [EnvironmentValueCheck] on the repository.
 *
 * @param repository The storage.
 * @param cipher The secrets key.
 */
class EnvironmentValueCheckImpl(
    private val repository: EnvironmentRepository,
    private val cipher: SecretCipher,
) : EnvironmentValueCheck {
    override suspend fun matches(orgId: Uuid, variantId: Uuid, memberId: Uuid?, value: SecretValue): Boolean {
        val plaintext = if (memberId == null) {
            repository.get(orgId, variantId)?.envelope?.let { cipher.open(it, environmentContext(orgId, variantId)) }
        } else {
            repository.assignedValues(orgId, memberId)
                .firstOrNull { it.variantId == variantId && it.binding == EnvironmentBinding.PER_MEMBER }
                ?.envelope
                ?.let { cipher.open(it, environmentContext(orgId, variantId, memberId)) }
        }
        return plaintext != null && plaintext.decodeToString() == value.reveal()
    }
}
