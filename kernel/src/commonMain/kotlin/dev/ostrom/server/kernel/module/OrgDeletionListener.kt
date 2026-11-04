// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.module

import kotlin.uuid.Uuid

/**
 * Told of an organization's deletion, so that a module stops what it does or costs for it, then erases what it keeps
 * of it. The orgs module deletes an organization logically (its members lose access at once), calls [deleted] on every
 * listener bound in Koin within a minute, and [purge] once the 30 days of the logical deletion are over. Both are
 * called at least once, again after a failure, so both are idempotent; neither ever touches the audit log, which is
 * kept.
 */
interface OrgDeletionListener {
    /**
     * The organization was deleted: stop what acts or costs for it (its subscription, its schedules).
     *
     * @param orgId The organization.
     */
    suspend fun deleted(orgId: Uuid)

    /**
     * The 30 days are over: erase the organization's data for good, except what the law keeps (invoices).
     *
     * @param orgId The organization.
     */
    suspend fun purge(orgId: Uuid)
}
