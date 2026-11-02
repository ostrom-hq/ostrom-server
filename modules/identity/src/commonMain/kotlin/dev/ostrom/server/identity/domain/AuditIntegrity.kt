// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.domain

import dev.ostrom.server.kernel.signing.InstanceKeyring
import kotlin.io.encoding.Base64

/**
 * An event of an organization's chain as read, with the hash of the event just before it in the chain (`sequence - 1`),
 * read even when a filter leaves that one out of the page.
 *
 * @property event The event.
 * @property predecessorHash The hash of the previous event of the chain, or null when there is none in the table.
 */
data class ChainedAuditEvent(val event: AuditEvent, val predecessorHash: String?)

/** What the server found when it read a line of the audit log (audit of the audit log, A2). */
enum class AuditIntegrity {
    /** The hash, the signature and the link to the previous line hold. */
    VERIFIED,

    /**
     * The line no longer matches its hash, or its signature does not verify with the key it names, or it names a key
     * the instance never had.
     */
    ALTERED,

    /** The previous line is missing, or is not the one this line was signed after: a line before it was erased. */
    UNLINKED,
}

/** Checks a line of the audit log against its canonical form, the instance key and the line before it. */
interface AuditVerifier {
    /**
     * Checks a line. Erasing the newest lines of a chain leaves a shorter chain that checks: nothing anchors its head.
     *
     * @param line The line, with the hash of the line before it.
     *
     * @return What was found; the first fault wins: a wrong hash, then a broken link, then a wrong signature.
     */
    suspend fun integrity(line: ChainedAuditEvent): AuditIntegrity
}

/**
 * [AuditVerifier] with every key of the instance, past ones included, and the hash [AuditLogImpl] signs with. The key
 * id is not in the canonical form: a line rewritten with another key id still has to verify with the key it names.
 *
 * @param keys The instance's keys.
 * @param hasher Hashes the canonical form (SHA-256).
 */
class AuditVerifierImpl(private val keys: InstanceKeyring, private val hasher: SecretHasher) : AuditVerifier {
    override suspend fun integrity(line: ChainedAuditEvent): AuditIntegrity {
        val event = line.event
        val canonical = auditCanonicalForm(event.id, event.sequence, event.entry, event.occurredAt, event.previousHash)
        // The first line of a chain follows nothing; any other follows the line before it, as it was signed.
        val linked = if (event.sequence == 1L) {
            event.previousHash == null && line.predecessorHash == null
        } else {
            line.predecessorHash != null && line.predecessorHash == event.previousHash
        }
        return when {
            hasher.hash(canonical) != event.hash -> AuditIntegrity.ALTERED
            !linked -> AuditIntegrity.UNLINKED
            signedByTheInstance(event, canonical) -> AuditIntegrity.VERIFIED
            else -> AuditIntegrity.ALTERED
        }
    }

    // An unknown key id (null) is no key of the instance: not its signature.
    private suspend fun signedByTheInstance(event: AuditEvent, canonical: String): Boolean =
        keys.verify(event.keyId, canonical.encodeToByteArray(), signatureOf(event)) == true

    // A signature that is not base64 is no signature: an empty one never verifies.
    private fun signatureOf(event: AuditEvent): ByteArray = try {
        Base64.decode(event.signature)
    } catch (_: IllegalArgumentException) {
        ByteArray(0)
    }
}
