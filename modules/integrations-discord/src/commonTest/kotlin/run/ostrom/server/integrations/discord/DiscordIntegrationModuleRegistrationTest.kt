// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import run.ostrom.server.integrations.discord.domain.DiscordApi
import run.ostrom.server.integrations.discord.domain.DiscordCallException
import run.ostrom.server.integrations.discord.infrastructure.DiscordMigrations
import run.ostrom.server.integrations.discord.infrastructure.HttpDiscordApi
import run.ostrom.server.kernel.config.MapConfigSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DiscordIntegrationModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("integrations-discord", DiscordIntegrationModule.name)
        assertEquals(DiscordMigrations, DiscordIntegrationModule.migrations)
        val app = koinApplication { modules(DiscordIntegrationModule.koinModule) }
        // Discord's own API, on the module's outbound client: built without a call.
        assertIs<HttpDiscordApi>(app.koin.get<DiscordApi>())
        app.close()
    }

    @Test
    fun aBotTokenDiscordRefusesIsSaidAsSuchAndDiscordDownAsDown() = runTest {
        val api = FakeDiscordApi()
        assertNull(DiscordIntegrationModule.verify(api, BOT_TOKEN))
        assertEquals(
            "Discord refuses this bot token (discord_http_401).",
            DiscordIntegrationModule.verify(api, "another"),
        )
        api.failure = DiscordCallException("discord_unreachable", retryable = true)
        val down = DiscordIntegrationModule.verify(api, BOT_TOKEN)
        assertEquals("Discord does not answer; save again in a moment.", down)
    }

    @Test
    fun theLinksCallbackIsUnderThePublicUrlElseADevelopmentServers() {
        val public = MapConfigSource(mapOf("OSTROM_PUBLIC_URL" to " https://ostrom.example/ "))
        val callback = DiscordIntegrationModule.redirectUri(public)
        assertEquals("https://ostrom.example/api/v1/discord/link/callback", callback)
        assertEquals(
            "http://localhost:8080/api/v1/discord/link/callback",
            DiscordIntegrationModule.redirectUri(MapConfigSource(emptyMap())),
        )
    }
}
