// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.importer.domain

/** What an imported item is. */
enum class ImportKind {
    /** The agent prefix of MCP servers. */
    SETTING,

    /** An org or personal secret. */
    SECRET,

    /** An MCP server. */
    SERVER,

    /** A denied tool, as an argument policy. */
    POLICY,

    /** A member's assignment to a server. */
    ASSIGNMENT,

    /** A shared subscription account. */
    ACCOUNT,
}

/** What the import did with an item. */
enum class ImportAction {
    /** Would be written (dry run). */
    PLANNED,

    /** Written for the first time. */
    CREATED,

    /** Changed to match TeamCode. */
    UPDATED,

    /** Already as in TeamCode. */
    UNCHANGED,

    /** Not imported; the reason says why. */
    SKIPPED,

    /**
     * A shared account waiting for a reconnection, left so: reviving it tests TeamCode's tokens, which rotates them and
     * disconnects TeamCode, so only the cutover run does it.
     */
    REVIVE_AT_CUTOVER,
}

/** A shared account the import leaves to TeamCode's refresh (TeamCode's refresh ownership). */
enum class ImportHandover {
    /** Waits for a handover: TeamCode may still renew its tokens, so it serves nothing in Ostrom yet. */
    PENDING_HANDOVER,

    /** Serves with TeamCode's access token, never renewed by Ostrom: TeamCode keeps renewing its own copy. */
    ACCESS_ONLY,
}

/**
 * One line of the report.
 *
 * @property kind What it is.
 * @property name Its name in Ostrom (or in TeamCode when skipped before getting one).
 * @property action What was done.
 * @property reason Why it was skipped, or a warning.
 * @property handover For a shared account left to TeamCode's refresh, how.
 */
data class ImportItem(
    val kind: ImportKind,
    val name: String,
    val action: ImportAction,
    val reason: String? = null,
    val handover: ImportHandover? = null,
)

/**
 * The report of an import: one line per item, in the order they were applied. Running the import again reports
 * everything unchanged.
 *
 * @property items The lines.
 */
data class ImportReport(val items: List<ImportItem>) {
    /**
     * How many lines have this action.
     *
     * @param action The action.
     */
    fun count(action: ImportAction): Int = items.count { it.action == action }

    /**
     * How many shared accounts are left to TeamCode's refresh this way.
     *
     * @param handover How.
     */
    fun count(handover: ImportHandover): Int = items.count { it.handover == handover }
}
