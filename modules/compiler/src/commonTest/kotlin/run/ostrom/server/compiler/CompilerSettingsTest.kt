// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler

import run.ostrom.server.compiler.domain.models.CompilerSettings
import run.ostrom.server.compiler.infrastructure.DefaultCompilerSources
import run.ostrom.server.compiler.presentation.serverUrlOf
import run.ostrom.server.kernel.config.ConfigSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class CompilerSettingsTest {
    private fun sources(vararg values: Pair<String, String>) = DefaultCompilerSources(
        object : ConfigSource {
            override fun get(key: String): String? = values.toMap()[key]
        },
    )

    @Test
    fun theEnvironmentSetsThePinThePublishersAndTheProductionApprover() = runTest {
        val org = Uuid.random()
        val set = sources(
            "OSTROM_PIN" to "manual",
            "OSTROM_CODE_PUBLISHERS" to " Lead, ,ops ",
            "OSTROM_PROD_APPROVER" to "ops",
        )
        assertEquals(CompilerSettings(true, listOf("lead", "ops"), "ops"), set.settings(org))
        assertEquals(CompilerSettings(), sources("OSTROM_PIN" to "auto").settings(org))
        assertTrue(set.rules(org).isEmpty() && set.ruleDecisions(org).isEmpty() && set.repos(org).isEmpty())
    }

    @Test
    fun sessionsCallBackThePublicUrlElseTheOrigin() {
        assertEquals("https://ostrom.example", serverUrlOf("https://ostrom.example/") { "unused" })
        assertEquals("http://localhost:80", serverUrlOf(null) { "http://localhost:80" })
    }
}
