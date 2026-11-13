// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.admin

import run.ostrom.server.admin.domain.InstanceAdmin
import run.ostrom.server.admin.domain.InstanceAdminsRepository
import run.ostrom.server.admin.domain.InstanceSettingsRepository
import run.ostrom.server.admin.domain.RemoveOutcome
import run.ostrom.server.admin.domain.StoredSettings
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.settings.InstanceAccount
import run.ostrom.server.kernel.settings.InstanceAccounts
import run.ostrom.server.kernel.settings.InstanceAuditRecord
import run.ostrom.server.kernel.settings.InstanceAuditTrail
import run.ostrom.server.kernel.settings.SettingsField
import run.ostrom.server.kernel.settings.SettingsSection
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

val ALICE: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000a11c")
val BOB: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000b0b")
val CAROL: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000ca20")
val NOW: Instant = Instant.parse("2026-11-13T12:00:00Z")

/** A section of the tests: a sender, a key, an optional URL that must be https. */
val MAIL: SettingsSection = SettingsSection(
    "mail",
    listOf(
        SettingsField("from", required = true, legacyVariable = "OSTROM_MAIL_FROM"),
        SettingsField("apiKey", secret = true, required = true, legacyVariable = "OSTROM_RESEND_API_KEY"),
        SettingsField("url", check = { if (it.startsWith("https://")) null else "an https URL" }),
        SettingsField(
            "token",
            secret = true,
            legacyVariable = "OSTROM_MAIL_TOKEN",
            check = { if (it.length > 3) null else "too short" },
        ),
    ),
)

/** A section without a former variable. */
val PUSH: SettingsSection = SettingsSection("push", listOf(SettingsField("relay")))

/** A clock the tests move. */
class SettableClock(var now: Instant = NOW) : Clock {
    override fun now(): Instant = now

    /** Moves the clock. */
    fun advance(by: Duration) {
        now += by
    }
}

/** The administrators in memory, as `admin_instance_admins` keeps them. */
class MemoryInstanceAdmins : InstanceAdminsRepository {
    /** Every row, removed ones included (the removal time). */
    val rows = linkedMapOf<Uuid, Pair<InstanceAdmin, Instant?>>()

    override suspend fun list(): List<InstanceAdmin> =
        rows.values.filter { it.second == null }.map { it.first }.sortedBy { it.addedAt }

    override suspend fun isAdmin(userId: Uuid): Boolean = rows[userId]?.second == null && userId in rows

    override suspend fun addIfNeverSeen(admin: InstanceAdmin): Boolean {
        if (admin.userId in rows) return false
        rows[admin.userId] = admin to null
        return true
    }

    override suspend fun add(admin: InstanceAdmin): Boolean {
        if (isAdmin(admin.userId)) return false
        rows[admin.userId] = admin to null
        return true
    }

    override suspend fun remove(userId: Uuid, by: Uuid, at: Instant): RemoveOutcome = when {
        !isAdmin(userId) -> RemoveOutcome.NOT_FOUND
        list().size == 1 -> RemoveOutcome.LAST
        else -> {
            rows[userId] = rows.getValue(userId).first to at
            RemoveOutcome.REMOVED
        }
    }
}

/** The settings in memory, as `admin_settings` keeps them; [lose] makes the next write lose its race. */
class MemoryInstanceSettings : InstanceSettingsRepository {
    val rows = mutableMapOf<String, StoredSettings>()
    var reads = 0
    var lose = false

    /** Runs during the next read, after the row is taken: a save of this replica racing it. */
    var duringRead: (() -> Unit)? = null

    override suspend fun get(section: String): StoredSettings? {
        reads++
        val row = rows[section]
        duringRead?.also { duringRead = null }?.invoke()
        return row
    }

    override suspend fun all(): Map<String, StoredSettings> = rows.toMap()

    override suspend fun save(settings: StoredSettings): Boolean {
        if (lose || rows[settings.section]?.version != settings.version - 1) return false
        rows[settings.section] = settings
        return true
    }

    override suspend fun insertIfAbsent(settings: StoredSettings): Boolean {
        if (lose || settings.section in rows) return false
        rows[settings.section] = settings
        return true
    }
}

/** The accounts of the tests. */
class FakeAccounts(
    val accounts: List<InstanceAccount> = listOf(
        InstanceAccount(ALICE, "alice", "alice@example.com"),
        InstanceAccount(BOB, "bob", "bob@example.com"),
    ),
    val owners: Map<Uuid, Instant> = emptyMap(),
) : InstanceAccounts {
    override suspend fun byVerifiedEmail(email: String): InstanceAccount? = accounts.firstOrNull { it.email == email }

    override suspend fun describe(userIds: Collection<Uuid>): Map<Uuid, InstanceAccount> =
        accounts.filter { it.userId in userIds }.associateBy { it.userId }

    override suspend fun setupOwners(): Map<Uuid, Instant> = owners
}

/** The audit records written. */
class RecordingAudit : InstanceAuditTrail {
    val records = mutableListOf<InstanceAuditRecord>()

    override suspend fun record(record: InstanceAuditRecord) {
        records += record
    }
}

/** The warnings written, with their fields. */
class RecordingLogger : Logger {
    val events = mutableListOf<Triple<LogLevel, String, Map<String, Any?>>>()

    override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
        events += Triple(level, message, fields)
    }

    override fun with(fields: Map<String, Any?>): Logger = this
}
