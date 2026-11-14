// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.kernel.module.serverKoin
import run.ostrom.server.kernel.settings.DiscardingInstanceAuditTrail
import run.ostrom.server.kernel.settings.EmptyInstanceSettings
import run.ostrom.server.kernel.settings.IgnoringInstanceAdminGrants
import run.ostrom.server.kernel.settings.InstanceAccount
import run.ostrom.server.kernel.settings.InstanceAccounts
import run.ostrom.server.kernel.settings.InstanceAdminGrants
import run.ostrom.server.kernel.settings.InstanceAdministrators
import run.ostrom.server.kernel.settings.InstanceAuditRecord
import run.ostrom.server.kernel.settings.InstanceAuditTrail
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.NoInstanceAccounts
import run.ostrom.server.kernel.settings.NoInstanceAdministrators
import run.ostrom.server.kernel.settings.SettingsField
import run.ostrom.server.kernel.settings.SettingsSection
import run.ostrom.server.kernel.settings.SettingsValues
import kotlinx.coroutines.test.runTest
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class InstanceAdministrationTest {
    private class ProbeModule(override val name: String, override val koinModule: Module) : ServerModule

    private val kernel = module { single<Clock> { Clock.System } }

    @Test
    fun withoutTheAdminModuleNothingIsStoredAndNobodyAdministers() = runTest {
        val koin = serverKoin(kernel, emptyList())
        val someone = Uuid.random()

        assertIs<EmptyInstanceSettings>(koin.get<InstanceSettings>())
        assertTrue(koin.get<InstanceSettings>().read("mail").isEmpty)
        assertIs<NoInstanceAdministrators>(koin.get<InstanceAdministrators>())
        assertFalse(koin.get<InstanceAdministrators>().isAdmin(someone))
        assertIs<IgnoringInstanceAdminGrants>(koin.get<InstanceAdminGrants>())
        koin.get<InstanceAdminGrants>().grantSetupOwner(someone, Instant.fromEpochSeconds(0))
        assertIs<NoInstanceAccounts>(koin.get<InstanceAccounts>())
        assertNull(koin.get<InstanceAccounts>().byVerifiedEmail("a@example.com"))
        assertEquals(emptyMap(), koin.get<InstanceAccounts>().describe(listOf(someone)))
        assertEquals(emptyMap(), koin.get<InstanceAccounts>().setupOwners())
        assertEquals("ada", InstanceAccount(someone, "ada", null).login)
        assertIs<DiscardingInstanceAuditTrail>(koin.get<InstanceAuditTrail>())
        koin.get<InstanceAuditTrail>().record(InstanceAuditRecord(null, "instance.settings.saved", "settings", "mail"))
    }

    @Test
    fun aModuleReplacesEachDefault() {
        val settings = InstanceSettings { SettingsValues(mapOf("from" to "a@example.com")) }
        val admins = InstanceAdministrators { true }
        val admin = ProbeModule(
            "admin",
            module {
                single<InstanceSettings> { settings }
                single<InstanceAdministrators> { admins }
            },
        )
        val koin = serverKoin(kernel, listOf(admin))

        assertSame(settings, koin.get<InstanceSettings>())
        assertSame(admins, koin.get<InstanceAdministrators>())
    }

    @Test
    fun aFieldRefusesWhatItsCheckThenItsVerificationRefuse() = runTest {
        val plain = SettingsField("from")
        assertNull(plain.refusal("ops@acme.test"))
        val checked = SettingsField(
            "key",
            check = { if (it.startsWith("k_")) null else "starts with k_" },
            verify = { if (it == "k_good") null else "is not signed" },
        )
        assertEquals("starts with k_", checked.refusal("bad"), "The check first, without verifying")
        assertEquals("is not signed", checked.refusal("k_forged"))
        assertNull(checked.refusal("k_good"))
    }

    @Test
    fun aSectionNamesItsFieldsOnce() {
        val section = SettingsSection("mail", listOf(SettingsField("from"), SettingsField("apiKey", secret = true)))

        assertEquals("apiKey", section.field("apiKey")?.name)
        assertNull(section.field("other"))
        assertNull(section.fields.first().check("anything"))
        assertFailsWith<IllegalArgumentException> { SettingsSection("Mail", emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            SettingsSection("mail", listOf(SettingsField("from"), SettingsField("from")))
        }
    }

    @Test
    fun valuesNeverPrintWhatTheyHold() {
        val values = SettingsValues(mapOf("apiKey" to "re_secret", "from" to "a@example.com"))

        assertEquals("re_secret", values["apiKey"])
        assertNull(values["other"])
        assertEquals("SettingsValues(apiKey, from)", values.toString())
        assertFalse(values.isEmpty)
        assertEquals(SettingsValues(mapOf("apiKey" to "re_secret", "from" to "a@example.com")), values)
        val reordered = SettingsValues(mapOf("from" to "a@example.com", "apiKey" to "re_secret"))
        assertEquals(values.hashCode(), reordered.hashCode())
        assertNotEquals<Any>(values, "SettingsValues(apiKey, from)")
        assertTrue(SettingsValues.EMPTY.isEmpty)
        assertNotEquals(SettingsValues.EMPTY, values)
    }
}
