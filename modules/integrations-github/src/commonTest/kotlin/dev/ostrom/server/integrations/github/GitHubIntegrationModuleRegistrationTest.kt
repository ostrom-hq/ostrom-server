package dev.ostrom.server.integrations.github

import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class GitHubIntegrationModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("integrations-github", GitHubIntegrationModule.name)
        koinApplication { modules(GitHubIntegrationModule.koinModule) }.close()
    }
}
