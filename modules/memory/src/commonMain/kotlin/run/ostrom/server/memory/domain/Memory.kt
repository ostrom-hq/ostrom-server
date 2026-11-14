// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.domain

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Who reads an entry: every member of the organization, or its owner alone (no administrator reads a personal one). */
enum class MemoryScope(val wire: String) {
    TEAM("team"),
    PERSONAL("personal"),
    ;

    /** Parses a scope. */
    companion object {
        /**
         * @param value `team` or `personal`.
         * @return The scope.
         * @throws InvalidMemoryException For any other value.
         */
        fun parse(value: String): MemoryScope =
            entries.find { it.wire == value } ?: throw InvalidMemoryException("Use the team or personal scope.")
    }
}

/**
 * An entry's state. Only [ACTIVE] is listed, searched or read; [PROPOSED] and [REJECTED] are written by the proposal
 * flow, [SUPERSEDED] by the curation.
 */
enum class MemoryStatus(val wire: String) {
    ACTIVE("active"),
    PROPOSED("proposed"),
    SUPERSEDED("superseded"),
    REJECTED("rejected"),
}

/** Who wrote an entry: a person, an agent, or the curation. */
enum class MemoryOrigin(val wire: String) {
    HUMAN("human"),
    AGENT("agent"),
    CURATION("curation"),
}

/**
 * One remembered fact.
 *
 * @property ownerId The member a personal entry belongs to; null for an organization's entry.
 * @property sessionId The session an agent wrote it from, kept as provenance.
 */
data class MemoryEntry(
    val id: Uuid,
    val scope: MemoryScope,
    val ownerId: String?,
    val title: String,
    val summary: String,
    val content: String,
    val tags: List<String>,
    val refs: List<String>,
    val status: MemoryStatus,
    val origin: MemoryOrigin,
    val authorId: String,
    val sessionId: String?,
    val supersedesId: Uuid?,
    val expiresAt: Instant?,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastReadAt: Instant?,
    val readCount: Int,
)

/** What the index and a search show of an entry: never its content. */
data class MemoryIndexEntry(
    val id: Uuid,
    val scope: MemoryScope,
    val title: String,
    val summary: String,
    val tags: List<String>,
    val updatedAt: Instant,
)

/** A position in the index: entries changed before [updatedAt], or at the same time with a smaller [id]. */
data class MemoryCursor(val updatedAt: Instant, val id: Uuid)

/** A page of the index and the cursor of the next one. */
data class MemoryPage(val entries: List<MemoryIndexEntry>, val next: MemoryCursor?)

/** A new entry, as its writer sends it. */
data class NewMemory(
    val scope: MemoryScope,
    val title: String,
    val summary: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val refs: List<String> = emptyList(),
    val expiresAt: Instant? = null,
)

/** The new fields of an entry; [expectedVersion] must be the one read, so two editors never overwrite each other. */
data class MemoryUpdate(
    val expectedVersion: Int,
    val title: String,
    val summary: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val refs: List<String> = emptyList(),
    val expiresAt: Instant? = null,
)

/**
 * The member a memory request acts for.
 *
 * @property orgId The organization.
 * @property memberId The member.
 * @property admin Whether they administer the organization (a person's role; an agent never administers).
 * @property person Whether a person presents the credential (a browser session, the unlocked app, the CLI's sign-in):
 *   TeamCode's interactive login. Only a person writes the organization's memory directly or decides what joins it.
 * @property agent Whether the call is an agent's: an agent token, or a call the CLI's relay forwards for an agent.
 * @property principal The credential, which the audit log names.
 */
data class MemoryCaller(
    val orgId: Uuid,
    val memberId: String,
    val admin: Boolean,
    val person: Boolean,
    val agent: Boolean,
    val principal: AuthenticatedPrincipal,
)
