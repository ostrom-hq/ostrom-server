// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.instance.domain

/** Storage of the instance row and of its signing keys. */
interface InstanceRepository {
    /**
     * Reads the instance.
     *
     * @return The instance, or null before the first start.
     */
    suspend fun get(): Instance?

    /**
     * Creates the instance unless it exists; concurrent starts keep the first one.
     *
     * @param instance The instance to create.
     *
     * @return The stored instance, which is not [instance] when another start created it first.
     */
    suspend fun createIfAbsent(instance: Instance): Instance

    /**
     * Records a signing key unless it is known; its first-seen date never changes. A known key without a rotation
     * takes the rotation of [key]; a rotation, once recorded, never changes.
     *
     * @param key The key.
     */
    suspend fun registerKey(key: SigningKey)

    /**
     * Lists every key the instance has used, oldest first.
     *
     * @return The keys.
     */
    suspend fun listKeys(): List<SigningKey>
}
