// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.admin

import run.ostrom.server.admin.domain.AdminCaller
import run.ostrom.server.admin.domain.AdminSource
import run.ostrom.server.admin.domain.ImportLegacySettingsUseCaseImpl
import run.ostrom.server.admin.domain.InstanceAdmin
import run.ostrom.server.admin.domain.InstanceAdminRequiredException
import run.ostrom.server.admin.domain.InstanceAdminsUseCaseImpl
import run.ostrom.server.admin.domain.InstanceSettingsReader
import run.ostrom.server.admin.domain.InstanceSettingsUseCaseImpl
import run.ostrom.server.admin.domain.InvalidSettingsException
import run.ostrom.server.admin.domain.SettingsChange
import run.ostrom.server.admin.domain.SettingsConflictException
import run.ostrom.server.admin.domain.SettingsSectionNotFoundException
import run.ostrom.server.admin.domain.StoredSettings
import run.ostrom.server.admin.domain.settingContext
import run.ostrom.server.kernel.settings.SettingsValues
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class InstanceSettingsTest {
    private val cipher = AesGcmSecretCipher(ByteArray(32) { 7 })
    private val repository = MemoryInstanceSettings()
    private val admins = MemoryInstanceAdmins()
    private val audit = RecordingAudit()
    private val logger = RecordingLogger()
    private val clock = SettableClock()
    private val reader = InstanceSettingsReader(repository, cipher, logger, clock)
    private val settings = InstanceSettingsUseCaseImpl(
        listOf(PUSH, MAIL),
        repository,
        InstanceAdminsUseCaseImpl(admins, FakeAccounts(), audit, clock),
        cipher,
        reader,
        audit,
        clock,
    )
    private val alice = AdminCaller(ALICE, person = true)

    private suspend fun admin() = admins.addIfNeverSeen(InstanceAdmin(ALICE, AdminSource.SETUP, NOW))

    @Test
    fun anAdministratorReadsEverySectionByName() = runTest {
        admin()

        val list = settings.list(alice)

        assertEquals(listOf("mail", "push"), list.map { it.section.name })
        assertEquals(0, list.first().version)
        assertEquals(mapOf("apiKey" to false, "token" to false), list.first().secrets)
        assertFailsWith<SettingsSectionNotFoundException> { settings.get(alice, "other") }
        assertFailsWith<SettingsSectionNotFoundException> {
            settings.save(alice, "other", SettingsChange(0, emptyMap(), emptyMap()))
        }
    }

    @Test
    fun nobodyElseReadsOrSaves() = runTest {
        val bob = AdminCaller(BOB, person = true)

        assertFailsWith<InstanceAdminRequiredException> { settings.list(bob) }
        assertFailsWith<InstanceAdminRequiredException> { settings.get(bob, "mail") }
        assertFailsWith<InstanceAdminRequiredException> {
            settings.save(bob, "mail", SettingsChange(0, emptyMap(), emptyMap()))
        }
    }

    @Test
    fun aSecretIsSealedToItsSectionAndFieldAndNeverShownAgain() = runTest {
        admin()

        val saved = settings.save(
            alice,
            "mail",
            SettingsChange(0, mapOf("from" to "Ostrom <a@example.com>", "url" to ""), mapOf("apiKey" to "re_secret")),
        )

        assertEquals(1, saved.version)
        assertEquals(mapOf("from" to "Ostrom <a@example.com>"), saved.stored?.values)
        assertEquals(mapOf("apiKey" to true, "token" to false), saved.secrets)
        val envelope = repository.rows.getValue("mail").sealed.getValue("apiKey")
        assertFalse("re_secret" in envelope)
        assertEquals("re_secret", cipher.open(envelope, settingContext("mail", "apiKey"))?.decodeToString())
        assertNull(cipher.open(envelope, settingContext("mail", "token")))
        assertEquals(false, saved.stored?.importedFromEnvironment)
        assertFalse("re_secret" in saved.stored.toString())
        val record = audit.records.single()
        assertEquals("instance.settings.saved", record.event)
        assertEquals(mapOf("version" to "1", "changed" to "from", "secretsSet" to "apiKey"), record.details)
        assertEquals("re_secret", reader.read("mail")["apiKey"])
    }

    @Test
    fun anEmptySecretKeepsItAndNullRemovesIt() = runTest {
        admin()
        val first = SettingsChange(0, mapOf("from" to "a"), mapOf("apiKey" to "k1", "token" to "t123"))
        settings.save(alice, "mail", first)

        val kept = settings.save(alice, "mail", SettingsChange(1, mapOf("from" to "a"), mapOf("apiKey" to "")))
        val removed = settings.save(alice, "mail", SettingsChange(2, emptyMap(), mapOf("token" to null)))

        assertEquals(mapOf("apiKey" to true, "token" to true), kept.secrets)
        assertEquals(mapOf("apiKey" to true, "token" to false), removed.secrets)
        assertEquals(mapOf("version" to "2"), audit.records[1].details)
        assertEquals(
            mapOf("version" to "3", "changed" to "from", "secretsRemoved" to "token"),
            audit.records[2].details,
        )
        assertEquals(SettingsValues(mapOf("apiKey" to "k1")), reader.read("mail"))
    }

    @Test
    fun aSaveOverAnotherOneIsAConflict() = runTest {
        admin()
        settings.save(alice, "push", SettingsChange(0, mapOf("relay" to "a"), emptyMap()))

        assertFailsWith<SettingsConflictException> {
            settings.save(alice, "push", SettingsChange(0, mapOf("relay" to "b"), emptyMap()))
        }
        repository.lose = true
        assertFailsWith<SettingsConflictException> {
            settings.save(alice, "push", SettingsChange(1, mapOf("relay" to "b"), emptyMap()))
        }
        assertFailsWith<SettingsConflictException> {
            settings.save(alice, "mail", SettingsChange(0, emptyMap(), emptyMap()))
        }
        assertEquals("a", repository.rows.getValue("push").values["relay"])
    }

    @Test
    fun aFieldTheSectionDoesNotHaveOrAValueItRefusesIsNamedNeverEchoed() = runTest {
        admin()
        suspend fun refused(
            values: Map<String, String> = emptyMap(),
            secrets: Map<String, String?> = emptyMap(),
            version: Int = 0,
        ) =
            assertFailsWith<InvalidSettingsException> {
                settings.save(alice, "mail", SettingsChange(version, values, secrets))
            }.message.orEmpty()

        assertEquals("other is not a plain field of mail.", refused(values = mapOf("other" to "x")))
        assertEquals("apiKey is not a plain field of mail.", refused(values = mapOf("apiKey" to "x")))
        assertEquals("from is not a secret of mail.", refused(secrets = mapOf("from" to "x")))
        assertEquals("other is not a secret of mail.", refused(secrets = mapOf("other" to "x")))
        assertEquals("url: an https URL", refused(values = mapOf("url" to "http://secret.example")))
        assertEquals("token: too short", refused(secrets = mapOf("token" to "abc")))
        assertEquals("token: is not signed", refused(secrets = mapOf("token" to "forged")), "Verified on save")
        assertEquals("from is 4096 characters at most.", refused(values = mapOf("from" to "a".repeat(4097))))
        assertEquals("token is 16384 characters at most.", refused(secrets = mapOf("token" to "a".repeat(16385))))
        assertEquals("version is a version this section had.", refused(version = -1))
        assertEquals("version is a version this section had.", refused(version = Int.MAX_VALUE))
        assertTrue(repository.rows.isEmpty())
    }

    @Test
    fun twoSectionsOfOneNameAreRefusedAtStart() {
        assertFailsWith<IllegalArgumentException> {
            InstanceSettingsUseCaseImpl(listOf(MAIL, MAIL), repository, settingsAdmins(), cipher, reader, audit, clock)
        }
    }

    private fun settingsAdmins() = InstanceAdminsUseCaseImpl(admins, FakeAccounts(), audit, clock)

    @Test
    fun theReaderKeepsASectionThirtySecondsAndForgetsItOnASave() = runTest {
        assertTrue(reader.read("mail").isEmpty)
        clock.advance(31.seconds)
        assertEquals(
            "SettingsChange(version=0, values=[from], secrets=[apiKey])",
            SettingsChange(0, mapOf("from" to "a@example.com"), mapOf("apiKey" to "re_secret")).toString(),
        )
        admin()
        assertTrue(reader.read("push").isEmpty)
        settings.save(alice, "push", SettingsChange(0, mapOf("relay" to "a"), emptyMap()))
        assertEquals("a", reader.read("push")["relay"])
        val reads = repository.reads

        // Another replica saves: this one sees it after its 30 seconds.
        repository.rows["push"] = repository.rows.getValue("push").copy(values = mapOf("relay" to "b"), version = 2)
        assertEquals("a", reader.read("push")["relay"])
        assertEquals(reads, repository.reads)
        clock.advance(30.seconds)
        assertEquals("b", reader.read("push")["relay"])
    }

    @Test
    fun aReadThatASaveOvertakesIsNotKept() = runTest {
        admin()
        settings.save(alice, "push", SettingsChange(0, mapOf("relay" to "a"), emptyMap()))
        repository.duringRead = { reader.forget("push") }

        assertEquals("a", reader.read("push")["relay"])
        repository.rows["push"] = repository.rows.getValue("push").copy(values = mapOf("relay" to "b"), version = 2)

        assertEquals("b", reader.read("push")["relay"])
    }

    @Test
    fun aSecretThatNoLongerOpensIsLeftOutAndSaid() = runTest {
        repository.rows["mail"] =
            StoredSettings("mail", mapOf("from" to "a"), mapOf("apiKey" to "v1:AAAA"), 1, NOW, ALICE)

        val values = reader.read("mail")

        assertEquals(SettingsValues(mapOf("from" to "a")), values)
        assertEquals("apiKey", logger.events.single().third["fields"])
    }

    @Test
    fun theFormerVariablesAreImportedOnceSealedAndSaidDeprecated() = runTest {
        val import = ImportLegacySettingsUseCaseImpl(listOf(MAIL, PUSH), repository, cipher, audit, logger, clock)
        val environment = mapOf(
            "OSTROM_MAIL_FROM" to "Ostrom <a@example.com>",
            "OSTROM_RESEND_API_KEY" to "re_secret",
            // Refused by the field's rule: left out, never imported, with the reason.
            "OSTROM_MAIL_TOKEN" to "forged",
        )

        import(environment::get)
        import(environment::get)

        val stored = repository.rows.getValue("mail")
        assertTrue(stored.importedFromEnvironment)
        assertEquals(1, stored.version)
        assertEquals(mapOf("from" to "Ostrom <a@example.com>"), stored.values)
        assertEquals(setOf("apiKey"), stored.sealed.keys)
        assertEquals("re_secret", reader.read("mail")["apiKey"])
        assertEquals("instance.settings.imported", audit.records.single().event)
        assertEquals(mapOf("fields" to "from,apiKey"), audit.records.single().details)
        assertEquals(4, logger.events.size)
        assertTrue("were not imported" in logger.events[0].second)
        assertEquals("OSTROM_MAIL_TOKEN", logger.events[0].third["variables"])
        assertEquals("OSTROM_MAIL_TOKEN: is not signed", logger.events[0].third["reasons"])
        assertTrue("Imported" in logger.events[1].second)
        assertTrue("Ignored" in logger.events[3].second)
        assertEquals("OSTROM_MAIL_FROM,OSTROM_RESEND_API_KEY", logger.events[3].third["variables"])
        assertFalse(logger.events.any { "re_secret" in it.third.values.toString() })
    }

    @Test
    fun nothingIsImportedWithoutAVariableOrWhenAnotherReplicaWasFirst() = runTest {
        val import = ImportLegacySettingsUseCaseImpl(listOf(MAIL, PUSH), repository, cipher, audit, logger, clock)

        import { null }
        // An empty variable is an unset one.
        import(mapOf("OSTROM_MAIL_FROM" to "")::get)
        repository.lose = true
        import(mapOf("OSTROM_MAIL_FROM" to "a")::get)

        assertTrue(repository.rows.isEmpty())
        assertTrue(audit.records.isEmpty())
        assertTrue(logger.events.isEmpty())
    }
}
