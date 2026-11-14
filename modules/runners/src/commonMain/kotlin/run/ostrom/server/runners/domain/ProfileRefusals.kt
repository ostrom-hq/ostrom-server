// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import run.ostrom.server.kernel.errors.OstromException

/**
 * The refusals of a session's cloud profile at its creation ([SessionProfiles.choose]), by their stable key: what an
 * automation run records as its `reason` and a workflow run as its `failureCode` when the session it asks for is
 * refused (TeamCode's `profile_unassigned`, `profile_not_found`…).
 */
object ProfileRefusals {
    /** Their keys. */
    val KEYS: Set<String> = setOf(
        "cloud_profile_not_found",
        "profile_unassigned",
        "profile_required",
        "image_not_allowed",
        "cluster_unavailable",
        "namespace_not_allowed",
    )

    /**
     * The key of a refusal of the profile.
     *
     * @param refused A refusal of the creation.
     *
     * @return Its key, or null when it is not one of the profile's.
     */
    fun keyOf(refused: OstromException): String? = refused.type.takeIf { it in KEYS }
}
