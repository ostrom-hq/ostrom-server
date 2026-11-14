// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.infrastructure

import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.notifications.domain.PushCredential
import run.ostrom.server.notifications.domain.PushDevice
import run.ostrom.server.notifications.domain.PushDevicesRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlin.uuid.Uuid

/**
 * [PushDevicesRepository] on PostgreSQL (the table of [NotificationsMigrations]), every query in a transaction scoped
 * to one organization by `ostrom.org_id`, which row level security enforces.
 *
 * @param driver The connection pool.
 */
class PushDevicesRepositoryImpl(private val driver: Driver) : PushDevicesRepository {
    override suspend fun save(device: PushDevice, keep: Int): PushDevice = driver.withOrganization(device.orgId) { tx ->
        val credential = device.credential
        val upsert = Statement.create(UPSERT).let {
            if (device.locale == null) it.bindNull("locale", String::class) else it.bind("locale", device.locale)
        }.let {
            val key = device.publicKey
            if (key == null) it.bindNull("public_key", String::class) else it.bind("public_key", key)
        }.let {
            if (credential == null) {
                it.bindNull("user_id", Uuid::class).bindNull("credential_id", Uuid::class)
            } else {
                it.bind("user_id", credential.userId).bind("credential_id", credential.credentialId)
            }
        }.bind("browser", credential?.browser == true)
        val saved = tx.fetchAll(
            upsert.bind("org_id", device.orgId).bind("id", device.id).bind("member_id", device.memberId)
                .bind("push_token", device.pushToken).bind("provider", device.provider)
                .bind("platform", device.platform).bind("created_at", device.createdAt)
                .bind("seen_at", device.seenAt),
        ).getOrThrow().first().toDevice()
        tx.execute(
            Statement.create(KEEP_LATEST_SQL).bind("org_id", device.orgId).bind("member_id", device.memberId)
                .bind("keep", keep),
        ).getOrThrow()
        saved
    }

    override suspend fun delete(orgId: Uuid, memberId: Uuid, id: Uuid): Boolean = driver.withOrganization(orgId) { tx ->
        tx.execute(Statement.create(DELETE_SQL).bind("org_id", orgId).bind("member_id", memberId).bind("id", id))
            .getOrThrow() > 0
    }

    override suspend fun ofMember(orgId: Uuid, memberId: Uuid): List<PushDevice> =
        driver.withOrganization(orgId) { tx ->
            tx.fetchAll(Statement.create(OF_MEMBER_SQL).bind("org_id", orgId).bind("member_id", memberId))
                .getOrThrow().map { it.toDevice() }
        }

    override suspend fun forget(orgId: Uuid, userId: Uuid, credentialId: Uuid?) {
        driver.withOrganization(orgId) { tx ->
            val statement = if (credentialId == null) {
                Statement.create(FORGET_BROWSERS_SQL)
            } else {
                Statement.create(FORGET_CREDENTIAL_SQL).bind("credential_id", credentialId)
            }
            tx.execute(statement.bind("org_id", orgId).bind("user_id", userId)).getOrThrow()
        }
    }

    override suspend fun forgetToken(orgId: Uuid, id: Uuid, pushToken: String) {
        driver.withOrganization(orgId) { tx ->
            tx.execute(
                Statement.create(FORGET_TOKEN_SQL).bind("org_id", orgId).bind("id", id).bind("push_token", pushToken),
            ).getOrThrow()
        }
    }

    override suspend fun deleteAll(orgId: Uuid) {
        driver.withOrganization(orgId) { tx ->
            tx.execute(Statement.create(DELETE_ALL_SQL).bind("org_id", orgId)).getOrThrow()
        }
    }

    private companion object {
        fun ResultSet.Row.toDevice() = PushDevice(
            id = get("id").asUuid(),
            orgId = get("org_id").asUuid(),
            memberId = get("member_id").asUuid(),
            pushToken = get("push_token").asString(),
            provider = get("provider").asString(),
            platform = get("platform").asString(),
            locale = get("locale").asStringOrNull(),
            createdAt = get("created_at").asInstant(),
            seenAt = get("seen_at").asInstant(),
            credential = get("credential_id").asStringOrNull()?.let { credentialId ->
                PushCredential(get("user_id").asUuid(), Uuid.parse(credentialId), get("browser").asBoolean())
            },
            publicKey = get("public_key").asStringOrNull(),
        )
    }
}

private const val COLUMNS = "org_id, id, member_id, push_token, provider, platform, locale, created_at, seen_at, " +
    "user_id, credential_id, browser, public_key"

// A token registered again (a new language, a reinstall, another member signing in on the phone) keeps its row, its id
// and its creation date.
private const val UPSERT = "INSERT INTO notifications_push_devices ($COLUMNS) VALUES (:org_id, :id, :member_id, " +
    ":push_token, :provider, :platform, :locale, :created_at, :seen_at, :user_id, :credential_id, :browser, " +
    ":public_key) " +
    "ON CONFLICT (org_id, push_token) DO UPDATE SET member_id = EXCLUDED.member_id, provider = EXCLUDED.provider, " +
    "platform = EXCLUDED.platform, locale = EXCLUDED.locale, seen_at = EXCLUDED.seen_at, " +
    "public_key = EXCLUDED.public_key, " +
    // A registration without a credential (an older app, another kind of caller) keeps the one the phone had.
    "user_id = COALESCE(EXCLUDED.user_id, notifications_push_devices.user_id), " +
    "credential_id = COALESCE(EXCLUDED.credential_id, notifications_push_devices.credential_id), " +
    "browser = CASE WHEN EXCLUDED.credential_id IS NULL THEN notifications_push_devices.browser " +
    "ELSE EXCLUDED.browser END RETURNING $COLUMNS"

/** Keeps a member's `:keep` most recently seen phones of the organization (`:org_id`, `:member_id`). */
internal const val KEEP_LATEST_SQL = "DELETE FROM notifications_push_devices WHERE org_id = :org_id " +
    "AND member_id = :member_id AND id NOT IN (SELECT id FROM notifications_push_devices " +
    "WHERE org_id = :org_id AND member_id = :member_id ORDER BY seen_at DESC, id DESC LIMIT :keep)"

/** Removes one of a member's phones (`:org_id`, `:member_id`, `:id`). */
internal const val DELETE_SQL = "DELETE FROM notifications_push_devices WHERE org_id = :org_id " +
    "AND member_id = :member_id AND id = :id"

/** A member's phones (`:org_id`, `:member_id`), the most recently seen first. */
internal const val OF_MEMBER_SQL = "SELECT $COLUMNS FROM notifications_push_devices WHERE org_id = :org_id " +
    "AND member_id = :member_id ORDER BY seen_at DESC, id DESC"

/** The phones a person registered with one credential (`:org_id`, `:user_id`, `:credential_id`). */
internal const val FORGET_CREDENTIAL_SQL = "DELETE FROM notifications_push_devices WHERE org_id = :org_id " +
    "AND user_id = :user_id AND credential_id = :credential_id"

/** The phones a person registered with a browser session (`:org_id`, `:user_id`). */
internal const val FORGET_BROWSERS_SQL = "DELETE FROM notifications_push_devices WHERE org_id = :org_id " +
    "AND user_id = :user_id AND browser"

/** A phone whose token the provider no longer knows, while it still has it (`:org_id`, `:id`, `:push_token`). */
internal const val FORGET_TOKEN_SQL = "DELETE FROM notifications_push_devices WHERE org_id = :org_id " +
    "AND id = :id AND push_token = :push_token"

private const val DELETE_ALL_SQL = "DELETE FROM notifications_push_devices WHERE org_id = :org_id"
