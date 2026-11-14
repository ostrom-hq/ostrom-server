// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity

import run.ostrom.server.identity.domain.GitHubAppOAuthClient
import run.ostrom.server.identity.domain.GitHubAppSignIn
import run.ostrom.server.identity.domain.MailSender
import run.ostrom.server.identity.domain.SignInMethodsSource
import run.ostrom.server.identity.domain.SignUpMode
import run.ostrom.server.identity.infrastructure.SignInSettings
import run.ostrom.server.identity.domain.MailAvailability
import run.ostrom.server.identity.infrastructure.InstanceMailSender
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsSection
import run.ostrom.server.testing.TestInstanceSettings
import kotlinx.coroutines.test.runTest
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

class IdentityModuleRegistrationTest {
    private val settings = TestInstanceSettings()

    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("identity", IdentityModule.name)
        koinApplication { modules(IdentityModule.koinModule) }.close()
    }

    @Test
    fun theSignInAndSignUpSectionsAreDeclaredForTheConsole() = withIdentity { koin ->
        val sections = koin.getAll<SettingsSection>().map { it.name }.sorted()

        assertEquals(listOf("github-sign-in", "google", "mail", "sign-up"), sections)
    }

    @Test
    fun providersSetInTheConsoleAreOfferedWithoutARestart() = withIdentity("OSTROM_RESEND_API_KEY" to "re_x") { koin ->
        val methods = koin.get<SignInMethodsSource>()
        assertEquals(emptyList(), methods.current().keys, "The former variable is the admin module's to import")
        settings.save("mail", mapOf("apiKey" to "re_x"))
        assertEquals(listOf("email"), methods.current().keys)
        assertEquals(SignUpMode.INVITE, koin.get<SignInSettings>().signUp())

        settings.save("github-sign-in", mapOf("clientId" to "Iv1.a", "clientSecret" to "s1"))
        settings.save("google", mapOf("clientId" to "g"))
        settings.save("sign-up", mapOf("mode" to "open"))

        assertEquals(listOf("github", "email"), methods.current().keys)
        assertEquals(SignUpMode.OPEN, koin.get<SignInSettings>().signUp())
        settings.save("google", mapOf("clientId" to "g", "clientSecret" to "s2"))
        assertEquals(listOf("github", "google", "email"), methods.current().keys)
        assertIs<InstanceMailSender>(koin.get<MailSender>())
    }

    @Test
    fun aMailDirectoryAloneOffersTheEmailLink() = withIdentity("OSTROM_MAIL_DIR" to "/tmp/ostrom-mail") { koin ->
        assertEquals(listOf("email"), koin.get<SignInMethodsSource>().current().keys)
        assertTrue(koin.get<MailAvailability>().available())
    }

    @Test
    fun withoutConfigurationNothingIsOffered() = withIdentity { koin ->
        assertEquals(emptyList(), koin.get<SignInMethodsSource>().current().keys)
        assertFalse(koin.get<MailAvailability>().available())
    }

    @Test
    fun theGitHubAppsClientBoundByItsModuleSignsPeopleInFirst() = runTest {
        val client = GitHubAppOAuthClient("Iv1.app", "app-secret", "https://gh.test", "https://api.gh.test")
        val integration = module { single<GitHubAppSignIn> { GitHubAppSignIn { client } } }
        val app = koinApplication { modules(kernel(), integration, IdentityModule.koinModule) }
        try {
            assertEquals("Iv1.app", app.koin.get<SignInSettings>().github()?.client?.clientId)
        } finally {
            app.close()
        }
    }

    private fun kernel(vararg config: Pair<String, String>) = module {
        single<ConfigSource> { MapConfigSource(config.toMap()) }
        single<Clock> { Clock.System }
        single<InstanceSettings> { settings }
    }

    private fun withIdentity(vararg config: Pair<String, String>, block: suspend (Koin) -> Unit) = runTest {
        val app = koinApplication { modules(kernel(*config), IdentityModule.koinModule) }
        try {
            block(app.koin)
        } finally {
            app.close()
        }
    }
}
