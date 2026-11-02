// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.domain

import dev.ostrom.server.kernel.signing.InstanceSigner
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

    /** The line no longer matches its hash, or its signature. */
    ALTERED,

    /** The previous line is missing, or is not the one this line was signed after: a line before it was erased. */
    UNLINKED,

    /** Signed by a key the server does not hold: nothing else is wrong with it. */
    UNVERIFIABLE,
}

/** Checks a line of the audit log against its canonical form, the instance key and the line before it. */
interface AuditVerifier {
    /**
     * Checks a line.
     *
     * @param line The line, with the hash of the line before it.
     *
     * @return What was found; the first fault wins: altered, then unlinked, then unverifiable.
     */
    suspend fun integrity(line: ChainedAuditEvent): AuditIntegrity
}

/**
 * [AuditVerifier] with the instance key and the hash [AuditLogImpl] signs with.
 *
 * @param signer The instance key.
 * @param hasher Hashes the canonical form (SHA-256).
 */
class AuditVerifierImpl(private val signer: InstanceSigner, private val hasher: SecretHasher) : AuditVerifier {
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
            event.keyId != signer.keyId -> AuditIntegrity.UNVERIFIABLE
            !signer.verify(canonical.encodeToByteArray(), signatureOf(event)) -> AuditIntegrity.ALTERED
            else -> AuditIntegrity.VERIFIED
        }
    }

    // A signature that is not base64 is no signature: an empty one never verifies.
    private fun signatureOf(event: AuditEvent): ByteArray = try {
        Base64.decode(event.signature)
    } catch (_: IllegalArgumentException) {
        ByteArray(0)
    }
}
