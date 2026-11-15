// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.instance.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

// The tables of [InstanceMigrations], as Exposed reads and writes them: the migrations create the schema, these
// objects only name its columns. All are the instance's, with no organization and no row-level security.

/** The instance's identity, one row (`singleton` is always true). */
internal object InstanceInfo : Table("instance_info") {
    val singleton = bool("singleton")
    val id = uuid("id")
    val createdAt = instant("created_at")
    override val primaryKey = PrimaryKey(singleton)
}

/** The public keys the instance has signed with, and the signature of each rotation. */
internal object InstanceSigningKeys : Table("instance_signing_keys") {
    val keyId = text("key_id")
    val algorithm = text("algorithm")
    val publicKeyPem = text("public_key_pem")
    val firstSeenAt = instant("first_seen_at")
    val rotationKeyId = text("rotation_key_id").nullable()
    val rotationSignature = text("rotation_signature").nullable()
    override val primaryKey = PrimaryKey(keyId)
}

/** The leases of periodic work, one row per job ([ScheduledWorkImpl]). */
internal object InstanceScheduledWork : Table("instance_scheduled_work") {
    val name = text("name")
    val owner = uuid("owner").nullable()
    val availableAt = instant("available_at")
    val lastStartedAt = instant("last_started_at").nullable()
    override val primaryKey = PrimaryKey(name)
}
