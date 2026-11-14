// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.testing

import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsValues
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class TestInstanceSettingsTest {
    @Test
    fun aSavedSectionIsReadAndAnAbsentOneIsEmpty() = runTest {
        val settings = TestInstanceSettings.openSignUp()

        assertEquals("open", settings.read("sign-up")["mode"])
        assertEquals(SettingsValues.EMPTY, settings.read("mail"))
        settings.save("mail", mapOf("from" to "a@example.com"))
        assertEquals("a@example.com", settings.read("mail")["from"])
        assertEquals(SettingsValues.EMPTY, TestInstanceSettings().read("sign-up"))
    }

    @Test
    fun aTestServerWithoutTheAdminModuleIsAnOpenInstance() = runTest {
        val kernel = module { single { "kernel" } }
        val identity = named("identity")

        val app = koinApplication { modules(testKernel(kernel, listOf(identity))) }
        try {
            assertEquals("kernel", app.koin.get<String>())
            val settings = assertIs<TestInstanceSettings>(app.koin.get<InstanceSettings>())
            assertEquals("open", settings.read("sign-up")["mode"])
        } finally {
            app.close()
        }
        assertSame(kernel, testKernel(kernel, listOf(identity, named("admin"))))
        val admin = koinApplication { modules(testKernel(kernel, listOf(named("admin")))) }
        assertNull(admin.koin.getOrNull<InstanceSettings>())
        admin.close()
    }

    private fun named(name: String) = object : ServerModule {
        override val name: String = name
        override val koinModule = module { }
    }
}
