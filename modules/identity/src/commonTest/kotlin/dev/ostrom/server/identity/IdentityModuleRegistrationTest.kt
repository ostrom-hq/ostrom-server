// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.MailSender
import dev.ostrom.server.identity.domain.SignInMethods
import dev.ostrom.server.identity.infrastructure.DirectoryMailSender
import dev.ostrom.server.identity.infrastructure.ResendMailSender
import dev.ostrom.server.identity.infrastructure.UnconfiguredMailSender
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.MapConfigSource
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock

class IdentityModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("identity", IdentityModule.name)
        koinApplication { modules(IdentityModule.koinModule) }.close()
    }

    @Test
    fun configuredProvidersAndMailAreOfferedAndTheHttpClientIsClosedWithKoin() = withIdentity(
        "OSTROM_GITHUB_CLIENT_ID" to "Iv1.a", "OSTROM_GITHUB_CLIENT_SECRET" to "s1",
        "OSTROM_GOOGLE_CLIENT_ID" to "g", "OSTROM_GOOGLE_CLIENT_SECRET" to "s2",
        "OSTROM_RESEND_API_KEY" to "re_x",
    ) { koin ->
        assertEquals(listOf("github", "google", "email"), koin.get<SignInMethods>().keys)
        assertIs<ResendMailSender>(koin.get<MailSender>())
    }

    @Test
    fun aMailDirectoryAloneOffersTheEmailLink() = withIdentity("OSTROM_MAIL_DIR" to "/tmp/ostrom-mail") { koin ->
        assertEquals(listOf("email"), koin.get<SignInMethods>().keys)
        assertIs<DirectoryMailSender>(koin.get<MailSender>())
    }

    @Test
    fun withoutConfigurationNothingIsOffered() = withIdentity { koin ->
        assertEquals(emptyList(), koin.get<SignInMethods>().keys)
        assertIs<UnconfiguredMailSender>(koin.get<MailSender>())
    }

    private fun withIdentity(vararg config: Pair<String, String>, block: (Koin) -> Unit) {
        val kernel = module {
            single<ConfigSource> { MapConfigSource(config.toMap()) }
            single<Clock> { Clock.System }
        }
        val app = koinApplication { modules(kernel, IdentityModule.koinModule) }
        try {
            block(app.koin)
        } finally {
            app.close()
        }
    }
}
