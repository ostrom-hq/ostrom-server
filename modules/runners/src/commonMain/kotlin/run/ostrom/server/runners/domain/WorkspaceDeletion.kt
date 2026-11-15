// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import kotlin.time.Instant

/**
 * Where the proof that a Kubernetes session's workspace is gone stands (TeamCode's `DeletionState`).
 *
 * @property wire The stable name in the API and the database.
 */
enum class DeletionState(val wire: String) {
    /** The session holds its workspace. */
    NONE("none"),

    /** Its claim is being deleted, then its PersistentVolume awaited. */
    PENDING("pending"),

    /** The claim and the PersistentVolume are gone: the provisioner deleted the provider's volume. */
    PROVEN("proven"),

    /** The PersistentVolume stays ([WorkspaceDeletion.problem]): checked again until an administrator deletes it. */
    BLOCKED("blocked"),
    ;

    /** Constants and readers of the enclosing type. */
    companion object {
        /**
         * @param wire A stored name.
         * @return The state; [NONE] for a name this version does not know.
         */
        fun fromWire(wire: String): DeletionState = entries.firstOrNull { it.wire == wire } ?: NONE
    }
}

/**
 * The proof that a Kubernetes session's workspace is gone, provider volume included (TeamCode's workspace deletion
 * fields, `runners.cloud.retention.v1`). The PersistentVolume bound to the claim and the provider's volume handle are
 * recorded before the claim is deleted, as evidence.
 *
 * @property state Where the proof stands.
 * @property claimDeletedAt When the claim was seen gone.
 * @property volumeDeletedAt When its PersistentVolume was seen gone; null when the claim was never bound.
 * @property volumeName The PersistentVolume bound to the claim.
 * @property volumeHandle The provider's volume handle (CSI `volumeHandle`).
 * @property problem Why the deletion is blocked: [VOLUME_RETAINED], [VOLUME_DELETION_STALLED] or
 *   [VOLUME_UNREADABLE].
 */
data class WorkspaceDeletion(
    val state: DeletionState = DeletionState.NONE,
    val claimDeletedAt: Instant? = null,
    val volumeDeletedAt: Instant? = null,
    val volumeName: String? = null,
    val volumeHandle: String? = null,
    val problem: SessionFailure? = null,
) {
    /** Constants and readers of the enclosing type. */
    companion object {
        /** The PersistentVolume's reclaim policy keeps the provider's volume: an administrator deletes it. */
        const val VOLUME_RETAINED: String = "volume_retained"

        /** The provisioner has not deleted the PersistentVolume within the bound, or it failed. */
        const val VOLUME_DELETION_STALLED: String = "volume_deletion_stalled"

        /**
         * The cluster's service account may not read PersistentVolumes (no ClusterRole on them): the claim is
         * deleted, but its volume's deletion cannot be proven until an administrator grants the read.
         */
        const val VOLUME_UNREADABLE: String = "volume_unreadable"
    }
}

/**
 * A storage class as the cluster reports it (TeamCode's `StorageClassObservation`).
 *
 * @property name Its name.
 * @property reclaimPolicy What happens to a volume once its claim is deleted: only `Delete` deletes the provider's.
 */
data class StorageClassObservation(val name: String, val reclaimPolicy: String)

/**
 * A session's workspace claim as the cluster reports it.
 *
 * @property volumeName The PersistentVolume bound to it; null while it is pending.
 */
data class ClaimObservation(val volumeName: String?)

/**
 * A PersistentVolume as the cluster reports it (TeamCode's `VolumeObservation`).
 *
 * @property name Its name.
 * @property phase `Bound`, `Released`, `Failed`…
 * @property reclaimPolicy `Delete`, `Retain`…
 * @property volumeHandle The provider's volume handle, for a CSI volume.
 * @property claimNamespace The namespace of the claim it was bound to.
 * @property claimName The claim it was bound to.
 */
data class VolumeObservation(
    val name: String,
    val phase: String,
    val reclaimPolicy: String,
    val volumeHandle: String? = null,
    val claimNamespace: String? = null,
    val claimName: String? = null,
)

/** The reclaim policy that deletes the provider's volume with its claim. */
const val RECLAIM_DELETE: String = "Delete"
