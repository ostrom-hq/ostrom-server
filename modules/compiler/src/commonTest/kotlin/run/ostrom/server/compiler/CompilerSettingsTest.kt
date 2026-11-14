// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler

import run.ostrom.server.compiler.domain.models.CompilerSettings
import run.ostrom.server.compiler.domain.models.CompilerSettingsChange
import run.ostrom.server.compiler.domain.models.StoredCompilerSettings
import run.ostrom.server.compiler.domain.usecases.CompilerSettingsUseCaseImpl
import run.ostrom.server.compiler.infrastructure.DefaultCompilerSources
import run.ostrom.server.compiler.presentation.CompilerSettingsResponse
import run.ostrom.server.compiler.presentation.SaveCompilerSettingsRequest
import run.ostrom.server.compiler.presentation.serverUrlOf
import run.ostrom.server.compiler.presentation.toChange
import run.ostrom.server.compiler.presentation.toResponse
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class CompilerSettingsTest {
    private val org = Uuid.parse("00000000-0000-0000-0000-00000000000a")
    private val repository = FakeCompilerSettingsRepository()
    private val audited = mutableListOf<OrgAuditRecord>()
    private val principal = AuthenticatedPrincipal("user:lead", PrincipalKind.USER, emptySet())
    private var now = Instant.parse("2026-11-14T10:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = now
    }
    private val settings = CompilerSettingsUseCaseImpl(repository, OrgAuditTrail { audited += it }, clock)

    private suspend fun save(change: CompilerSettingsChange, role: OrgRole? = OrgRole.ADMIN) =
        settings.save(org, "lead", role, principal, change)

    private suspend fun refused(change: CompilerSettingsChange, role: OrgRole? = OrgRole.ADMIN): OstromException =
        assertFailsWith { save(change, role) }

    @Test
    fun theCompilationReadsWhatTheOrganizationStoredElseTheDefaults() = runTest {
        val sources = DefaultCompilerSources(repository)
        assertEquals(CompilerSettings(), sources.settings(org))
        val stored = CompilerSettings(true, listOf("lead"), "ops")
        repository.stored[org] = StoredCompilerSettings(stored, "lead", now)
        assertEquals(stored, sources.settings(org))
        assertEquals(CompilerSettings(), sources.settings(Uuid.random()), "another organization's are its own")
        assertTrue(sources.rules(org).isEmpty() && sources.ruleDecisions(org).isEmpty() && sources.repos(org).isEmpty())
    }

    @Test
    fun theFormerVariablesStayTheDefaultOfAnOrganizationThatSavedNone() = runTest {
        val config = MapConfigSource(
            mapOf(
                "OSTROM_PIN" to "manual",
                "OSTROM_CODE_PUBLISHERS" to " Lead, ,ops ",
                "OSTROM_PROD_APPROVER" to "ops",
            ),
        )
        val legacy = DefaultCompilerSources.legacy(config)
        assertEquals(CompilerSettings(true, listOf("lead", "ops"), "ops"), legacy)
        val auto = MapConfigSource(mapOf("OSTROM_PIN" to "auto"))
        assertEquals(CompilerSettings(), DefaultCompilerSources.legacy(auto))
        val sources = DefaultCompilerSources(repository, legacy)
        assertEquals(legacy, sources.settings(org))
        repository.stored[org] = StoredCompilerSettings(CompilerSettings(), "lead", now)
        assertEquals(CompilerSettings(), sources.settings(org), "what the organization saved wins")
    }

    @Test
    fun anOwnerOrAnAdminSavesTheSettingsTrimmedAndAudited() = runTest {
        val first = save(CompilerSettingsChange("manual", listOf(" Lead", "ops ", "lead"), " ops "), OrgRole.OWNER)
        assertEquals(StoredCompilerSettings(CompilerSettings(true, listOf("lead", "ops"), "ops"), "lead", now), first)
        val record = audited.single()
        assertEquals("compiler.settings.changed", record.event)
        assertEquals("compiler_settings", record.subjectType)
        assertEquals("$org", record.subjectId)
        val facts = mapOf("pin" to "manual", "code_publishers" to "lead,ops", "prod_approver" to "ops")
        assertEquals(facts, record.details)

        now = Instant.parse("2026-11-14T11:00:00Z")
        val second = save(CompilerSettingsChange("auto", expected = first.updatedAt))
        assertEquals(CompilerSettings(), second.settings, "an omitted list or approver is removed")
        assertEquals(mapOf("pin" to "auto"), audited.last().details)
        assertEquals(second, settings.read(org))
    }

    @Test
    fun anEmptyListLetsNobodyPublishCode() = runTest {
        assertEquals(emptyList(), save(CompilerSettingsChange("auto", emptyList())).settings.codePublishers)
    }

    @Test
    fun aSaveOfAnotherVersionIsAConflict() = runTest {
        val first = save(CompilerSettingsChange("auto"))
        for (expected in listOf(null, Instant.parse("2026-01-01T00:00:00Z"))) {
            val conflict = refused(CompilerSettingsChange("manual", expected = expected))
            assertEquals(ErrorKind.CONFLICT, conflict.kind)
            assertEquals("compiler_settings_conflict", conflict.type)
        }
        assertEquals(first, settings.read(org))
        assertEquals(1, audited.size)
    }

    @Test
    fun onlyAnOwnerOrAnAdminSavesThem() = runTest {
        for (role in listOf(OrgRole.MEMBER, OrgRole.APPROVER, null)) {
            val forbidden = refused(CompilerSettingsChange("manual"), role)
            assertEquals(ErrorKind.FORBIDDEN, forbidden.kind, "$role")
            assertEquals("Only an owner or an admin changes how the organization compiles.", forbidden.message)
        }
        assertNull(settings.read(org))
    }

    @Test
    fun eachRefusedValueNamesItsField() = runTest {
        val long = "x".repeat(101)
        val publisher = "A code publisher is a login of 1 to 100 characters."
        val cases = mapOf(
            CompilerSettingsChange("sometimes") to "pin is auto or manual.",
            CompilerSettingsChange("auto", List(101) { "dev-$it" }) to "At most 100 code publishers.",
            CompilerSettingsChange("auto", listOf("lead", " ")) to publisher,
            CompilerSettingsChange("auto", listOf(long)) to publisher,
            CompilerSettingsChange("auto", prodApprover = " ") to "prodApprover is a login of 1 to 100 characters.",
            CompilerSettingsChange("auto", prodApprover = long) to "prodApprover is a login of 1 to 100 characters.",
        )
        for ((change, reason) in cases) {
            val invalid = refused(change)
            assertEquals(ErrorKind.INVALID_REQUEST, invalid.kind)
            assertEquals("invalid_compiler_settings", invalid.type)
            assertEquals(reason, invalid.message)
        }
        val longest = save(CompilerSettingsChange("auto", listOf("X".repeat(100))))
        assertEquals(listOf("x".repeat(100)), longest.settings.codePublishers)
        assertTrue(audited.size == 1)
    }

    @Test
    fun theAnswerWritesTheDefaultsAndTheVersion() {
        assertEquals(CompilerSettingsResponse("auto"), null.toResponse())
        val at = Instant.parse("2026-11-14T10:00:00.123456Z")
        val manual = StoredCompilerSettings(CompilerSettings(true, listOf("lead"), "ops"), "lead", at)
        assertEquals(
            CompilerSettingsResponse("manual", listOf("lead"), "ops", "2026-11-14T10:00:00.123456Z", "lead"),
            manual.toResponse(),
        )
        assertEquals("auto", StoredCompilerSettings(CompilerSettings(), "lead", at).toResponse().pin)
    }

    @Test
    fun aSaveNamesTheVersionItEditsAsADateTime() {
        assertEquals(CompilerSettingsChange("auto"), SaveCompilerSettingsRequest("auto").toChange())
        val at = "2026-11-14T10:00:00.123456Z"
        val change = SaveCompilerSettingsRequest("manual", listOf("lead"), "ops", at).toChange()
        assertEquals(CompilerSettingsChange("manual", listOf("lead"), "ops", Instant.parse(at)), change)
        val notADate = SaveCompilerSettingsRequest("auto", updatedAt = "now")
        val invalid = assertFailsWith<OstromException> { notADate.toChange() }
        assertEquals("invalid_compiler_settings", invalid.type)
        assertEquals("updatedAt is a date-time.", invalid.message)
    }

    @Test
    fun theFormerCompilePolicyVariablesWarnThatTheyAreDeprecated() = runTest {
        val lines = mutableListOf<String>()
        val logger = object : Logger {
            override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
                lines += "$level $message $fields"
            }
        }
        for (config in listOf(mapOf("OSTROM_PIN" to "manual", "OSTROM_PROD_APPROVER" to "ops"), emptyMap())) {
            val application = koinApplication {
                modules(
                    module {
                        single<ConfigSource> { MapConfigSource(config) }
                        single<Logger> { logger }
                    },
                    CompilerModule.koinModule,
                )
            }
            try {
                CompilerModule.initialize(application.koin)
            } finally {
                application.close()
            }
        }
        assertEquals(
            listOf(
                "WARN compile policy variables deprecated: the default of every organization that saved none, " +
                    "set it per organization in the console {variables=OSTROM_PIN,OSTROM_PROD_APPROVER}",
            ),
            lines,
        )
    }

    @Test
    fun sessionsCallBackThePublicUrlElseTheOrigin() {
        assertEquals("https://ostrom.example", serverUrlOf("https://ostrom.example/") { "unused" })
        assertEquals("http://localhost:80", serverUrlOf(null) { "http://localhost:80" })
    }
}
