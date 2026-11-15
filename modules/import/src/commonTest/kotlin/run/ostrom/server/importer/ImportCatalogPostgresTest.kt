// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer

import run.ostrom.server.hooks.HooksModule
import run.ostrom.server.hooks.domain.HookDefinition
import run.ostrom.server.hooks.domain.HookEvent
import run.ostrom.server.hooks.domain.ManageHookCatalogUseCase
import run.ostrom.server.hooks.domain.PublishHookVersionUseCase
import run.ostrom.server.importer.Importing.adminA
import run.ostrom.server.importer.Importing.memberA
import run.ostrom.server.importer.Importing.otherA
import run.ostrom.server.importer.domain.ImportAction
import run.ostrom.server.importer.domain.ImportAction.CREATED
import run.ostrom.server.importer.domain.ImportAction.PLANNED
import run.ostrom.server.importer.domain.ImportAction.SKIPPED
import run.ostrom.server.importer.domain.ImportAction.UNCHANGED
import run.ostrom.server.importer.domain.ImportAction.UPDATED
import run.ostrom.server.importer.presentation.ImportReportDto
import run.ostrom.server.importer.presentation.TeamCodeArtifactFileDto
import run.ostrom.server.importer.presentation.TeamCodeDecisionDto
import run.ostrom.server.importer.presentation.TeamCodeHookDto
import run.ostrom.server.importer.presentation.TeamCodeHookVersionDto
import run.ostrom.server.importer.presentation.TeamCodeImportRequest
import run.ostrom.server.importer.presentation.TeamCodeMemberDto
import run.ostrom.server.importer.presentation.TeamCodeSkillDto
import run.ostrom.server.importer.presentation.TeamCodeSkillVersionDto
import run.ostrom.server.importer.presentation.TeamCodeVariableAssignmentDto
import run.ostrom.server.importer.presentation.TeamCodeVariableDto
import run.ostrom.server.importer.presentation.TeamCodeVariableExclusionDto
import run.ostrom.server.kernel.auth.ALL_USER_SCOPES
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.mcprelay.McpRelayModule
import run.ostrom.server.runners.domain.AuthenticateRunnerUseCase
import run.ostrom.server.runners.domain.CheckRunnerTokenUseCase
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.RunnerUnauthenticatedException
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.EnvironmentActor
import run.ostrom.server.secrets.domain.EnvironmentBinding
import run.ostrom.server.secrets.domain.EnvironmentValueCheck
import run.ostrom.server.secrets.domain.ManageEnvironmentVariantsUseCase
import run.ostrom.server.secrets.domain.NewEnvironmentVariant
import run.ostrom.server.secrets.domain.ResolveEnvironmentUseCase
import run.ostrom.server.secrets.domain.SecretValue
import run.ostrom.server.skills.SkillsModule
import run.ostrom.server.skills.domain.ArtifactFile
import run.ostrom.server.skills.domain.CatalogCaller
import run.ostrom.server.skills.domain.CredentialKind
import run.ostrom.server.skills.domain.Decision
import run.ostrom.server.skills.domain.FileMode
import run.ostrom.server.skills.domain.ManageSkillCatalogUseCase
import run.ostrom.server.skills.domain.PublishSkillVersionUseCase
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.statement.bodyAsText
import org.koin.core.module.Module
import org.koin.dsl.module
import io.ktor.http.HttpStatusCode
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Real PostgreSQL and real HTTP: skills, hooks and environment variants written through their own modules. */
class ImportCatalogPostgresTest {
    @Test
    fun aTeamsCatalogIsImportedOnceAndASecondRunChangesNothing() = serverTest(modules, catalogOverrides()) { server ->
        with(server) {
            val dry = report(catalog(), dryRun = true)
            assertEquals(expected(PLANNED), dry.lines())
            assertTrue(koin.get<ManageSkillCatalogUseCase>().catalog(admin).isEmpty(), "A dry run writes nothing")
            val real = report(catalog())
            assertEquals(expected(CREATED), real.lines())
            assertEquals(expected(UNCHANGED), report(catalog()).lines())
            assertEquals(expected(UNCHANGED), report(catalog(), dryRun = true).lines())
            // No value ever reaches the report.
            val text = import(catalog()).bodyAsText()
            listOf(SHARED_VALUE, LENA_VALUE, PERSONAL_VALUE).forEach { assertFalse(it in text) }

            val skill = koin.get<ManageSkillCatalogUseCase>().versions(admin, "review")
            assertEquals("1.1.0", skill.skill.pinnedVersion)
            assertTrue(skill.skill.allMembers)
            assertEquals(setOf("1.0.0", "1.1.0"), skill.versions.map { it.version }.toSet())
            val decisions = koin.get<ManageSkillCatalogUseCase>().assignments(admin, "review")
            assertEquals(Decision.DENY, decisions.single { it.memberId == "${otherA.memberId}" }.decision)
            val personal = koin.get<ManageSkillCatalogUseCase>().catalog(admin).single { it.name == "notes" }
            assertEquals("${memberA.memberId}", personal.ownerId)
            assertEquals("0.1.0", personal.pinnedVersion)
            val hook = koin.get<ManageHookCatalogUseCase>().versions(admin, "guard").hook
            assertEquals("2", hook.pinnedVersion)
            assertFalse(hook.allMembers)

            val resolve = koin.get<ResolveEnvironmentUseCase>()
            val lena = resolve(orgA, memberA.memberId).values.mapValues { it.value.reveal() }
            val values = mapOf("GITHUB_TOKEN" to SHARED_VALUE, "NPM_TOKEN" to LENA_VALUE, "MY_KEY" to PERSONAL_VALUE)
            assertEquals(values, lena)
            // Vera is excluded from GITHUB_TOKEN, as in TeamCode.
            assertEquals(emptyMap(), resolve(orgA, otherA.memberId).values)
        }
    }

    @Test
    @Suppress("LongMethod") // One change of each kind, then its report, read top to bottom.
    fun aChangeInTeamCodeIsCarriedAndOnlyIt() = serverTest(modules, catalogOverrides()) { server ->
        with(server) {
            report(catalog())
            val changed = catalog(
                shared = "ghp-shared-value-2",
                lenaValue = "npm-lena-value-2",
                personalValue = "my-personal-value-2",
                variantName = "renamed",
                skillPin = null,
                skillAudience = false,
                hookPin = "1",
                hookAudience = true,
                extraSkillVersion = true,
                lenaDecision = "deny",
                hookOmitted = 2,
                lenaGithub = "v-alt",
            )
            val dry = report(changed, dryRun = true)
            val real = report(changed)
            assertEquals(dry.lines().size, real.lines().size)
            assertEquals(
                listOf(
                    line("variable", "GITHUB_TOKEN/renamed", UPDATED),
                    line("variable", "NPM_TOKEN/per-member", UNCHANGED),
                    line("variable", "MY_KEY/mine (personal, ${memberA.memberId})", UPDATED),
                    line("variable", "GITHUB_TOKEN/alt", CREATED),
                    line("exclusion", "GITHUB_TOKEN/${otherA.memberId}", UNCHANGED),
                    line("assignment", "GITHUB_TOKEN/${memberA.memberId}", UPDATED),
                    line("assignment", "NPM_TOKEN/${memberA.memberId}", UPDATED),
                    line("skill", "review", UPDATED, "1 older version stays in TeamCode."),
                    line("decision", "review/${otherA.memberId}", UNCHANGED),
                    line("decision", "review/${memberA.memberId}", UPDATED),
                    line("skill", "notes (personal, ${memberA.memberId})", UNCHANGED),
                    line("hook", "guard", UPDATED, "2 older versions stay in TeamCode."),
                    line("decision", "guard/${memberA.memberId}", UNCHANGED),
                ),
                real.lines(),
            )
            val planned = real.lines().map { if (it.action in setOf("updated", "created")) "planned" else it.action }
            assertEquals(planned, dry.lines().map { it.action })
            val skill = koin.get<ManageSkillCatalogUseCase>().versions(admin, "review").skill
            assertNull(skill.pinnedVersion)
            assertFalse(skill.allMembers)
            val hook = koin.get<ManageHookCatalogUseCase>().versions(admin, "guard").hook
            assertEquals("1", hook.pinnedVersion)
            assertTrue(hook.allMembers)
            val lena = koin.get<ResolveEnvironmentUseCase>()(orgA, memberA.memberId).values
                .mapValues { it.value.reveal() }
            assertEquals("npm-lena-value-2", lena["NPM_TOKEN"])
            assertEquals("ghp-alt-value-1", lena["GITHUB_TOKEN"])
            assertEquals("my-personal-value-2", lena["MY_KEY"])
            // Unpinning a personal skill goes through its owner.
            val unpinned = changed.copy(
                skills = changed.skills.map { it.copy(pinnedVersion = null) },
                hooks = changed.hooks.map { it.copy(pinnedVersion = null) },
            )
            val lines = report(unpinned).lines().associateBy { "${it.kind} ${it.name}" }
            assertEquals(UPDATED.name.lowercase(), lines.getValue("skill notes (personal, ${memberA.memberId})").action)
            assertEquals(UPDATED.name.lowercase(), lines.getValue("hook guard").action)
            assertNull(koin.get<ManageHookCatalogUseCase>().versions(admin, "guard").hook.pinnedVersion)
        }
    }

    @Test
    fun reservedNamesAndForeignItemsAreSkippedAsTheModulesRefuseThem() =
        serverTest(modules, catalogOverrides()) { server -> refusals(server) }

    @Suppress("LongMethod") // Each refusal the modules make, then the one report, read top to bottom.
    private suspend fun refusals(server: TestServer) {
        with(server) {
            koin.get<ManageEnvironmentVariantsUseCase>().create(
                EnvironmentActor(adminA, principal),
                NewEnvironmentVariant("GITHUB_TOKEN", "org", EnvironmentBinding.SHARED, SecretValue("hand-made-value")),
            )
            val publish = koin.get<PublishSkillVersionUseCase>()
            publish(admin, "review", "9.0.0", skillFiles("hand"), pin = false, allMembers = false)
            val definition = HookDefinition(setOf(HookEvent.STOP), null, "run.sh", 5)
            val script = listOf(ArtifactFile("run.sh", FileMode.EXECUTABLE, "#!/bin/sh\n".encodeToByteArray()))
            koin.get<PublishHookVersionUseCase>()(admin, "guard", "9", definition, script, false)
            val request = TeamCodeImportRequest(
                members = members,
                variables = listOf(
                    TeamCodeVariableDto("v-path", "PATH", "default", "shared", value = "/tmp/evil"),
                    TeamCodeVariableDto("v-ostrom", "OSTROM_TOKEN", "default", "shared", value = "x-value-1"),
                    TeamCodeVariableDto("v-org", "GITHUB_TOKEN", "ORG", "shared", value = "teamcode-value"),
                    TeamCodeVariableDto("v-ghost", "GHOST_KEY", "mine", "shared", "m3", "ghost-value"),
                ),
                variableAssignments = listOf(
                    TeamCodeVariableAssignmentDto("m1", "v-org"),
                    TeamCodeVariableAssignmentDto("m3", "v-org"),
                    TeamCodeVariableAssignmentDto("m1", "v-unknown"),
                ),
                variableExclusions = listOf(
                    TeamCodeVariableExclusionDto("m3", "GITHUB_TOKEN"),
                    TeamCodeVariableExclusionDto("m1", "LD_PRELOAD"),
                ),
                skills = listOf(
                    skill("review", decisions = listOf(TeamCodeDecisionDto("m1", "allow"))),
                    skill("old", retired = true),
                    skill("empty", versions = emptyList()),
                    skill("ghosts", ownerId = "m3"),
                    TeamCodeSkillDto(
                        "s-mode",
                        "linked",
                        versions = listOf(
                            TeamCodeSkillVersionDto("1", listOf(file("symlink", "x"))),
                        ),
                    ),
                    skill(
                        "odd",
                        decisions = listOf(
                            TeamCodeDecisionDto("m1", "maybe"),
                            TeamCodeDecisionDto("m3", "allow"),
                            TeamCodeDecisionDto("m9", "allow"),
                        ),
                    ),
                ),
                hooks = listOf(
                    hook("late", events = listOf("BeforeEverything")),
                    hook("guard"),
                    hook("bare", versions = emptyList(), decisions = listOf(TeamCodeDecisionDto("m1", "allow"))),
                ),
                memberMap = memberMap,
            )
            val expected = listOf(
                line("variable", "PATH/default", SKIPPED, RESERVED("PATH")),
                line("variable", "OSTROM_TOKEN/default", SKIPPED, RESERVED("OSTROM_TOKEN")),
                line("variable", "GITHUB_TOKEN/ORG", SKIPPED, FOREIGN_VARIANT),
                line("variable", "GHOST_KEY/mine (personal, m3)", SKIPPED, NO_OWNER),
                line("exclusion", "GITHUB_TOKEN/m3", SKIPPED, GHOST),
                line("exclusion", "LD_PRELOAD/${memberA.memberId}", SKIPPED, RESERVED("LD_PRELOAD")),
                line("assignment", "GITHUB_TOKEN/${memberA.memberId}", SKIPPED, "Its variant was not imported."),
                line("assignment", "GITHUB_TOKEN/m3", SKIPPED, GHOST),
                line("skill", "review", SKIPPED, "A skill named review exists and was not imported from TeamCode."),
                line("decision", "review/${memberA.memberId}", SKIPPED, NOT_IMPORTED),
                line("skill", "old", SKIPPED, "TeamCode retired it."),
                line("skill", "empty", SKIPPED, NO_VERSION),
                line("skill", "ghosts", SKIPPED, NO_OWNER),
                line("skill", "linked", SKIPPED, "Its file SKILL.md has a mode Ostrom does not keep (symlink)."),
                line("skill", "odd", CREATED),
                line("decision", "odd/${memberA.memberId}", SKIPPED, UNKNOWN_DECISION),
                line("decision", "odd/m3", SKIPPED, GHOST),
                line("decision", "odd/m9", SKIPPED, "No Ostrom member is mapped to m9."),
                line("hook", "late", SKIPPED, "Ostrom does not run hooks on BeforeEverything."),
                line("hook", "guard", SKIPPED, "A hook named guard exists and was not imported from TeamCode."),
                line("hook", "bare", SKIPPED, NO_VERSION),
                line("decision", "bare/${memberA.memberId}", SKIPPED, NOT_IMPORTED),
            )
            val planned = expected.map { if (it.action == "created") it.copy(action = "planned") else it }
            assertEquals(planned, report(request, dryRun = true).lines())
            assertEquals(expected, report(request).lines())
            // The hand-made variant keeps its value.
            val lena = koin.get<ResolveEnvironmentUseCase>()(orgA, memberA.memberId).values
            assertTrue(lena.isEmpty())
        }
    }

    @Test
    fun aVersionPublishedWithOtherContentOrAMovedVariantIsSkipped() =
        serverTest(modules, catalogOverrides()) { server ->
            with(server) {
                report(catalog())
                // Promoted in Ostrom since the import: no longer the member's, left alone.
                koin.get<ManageSkillCatalogUseCase>().promote(admin, "notes")
                val mine = koin.get<ManageEnvironmentVariantsUseCase>().list(adminA, personal = true).single()
                koin.get<ManageEnvironmentVariantsUseCase>().promote(EnvironmentActor(adminA, principal), mine.id)
                val conflicting = catalog().let { request ->
                    request.copy(
                        skills = request.skills.map { skill ->
                            val other = listOf(file("file", "# other"))
                            skill.copy(versions = skill.versions.map { it.copy(files = other) })
                        },
                        hooks = request.hooks.map { hook ->
                            hook.copy(versions = hook.versions.map { it.copy(timeoutSeconds = 30) })
                        },
                        variables = request.variables.map {
                            if (it.id == "v-npm") it.copy(binding = "shared", value = "npm-v-1234") else it
                        },
                    )
                }
                val items = report(conflicting).lines().associateBy { "${it.kind} ${it.name}" }
                assertEquals(SKIPPED.name.lowercase(), items.getValue("skill review").action)
                val review = items.getValue("skill review").reason.orEmpty()
                assertTrue(review.startsWith("This version is already published"), review)
                assertEquals(SKIPPED.name.lowercase(), items.getValue("hook guard").action)
                assertEquals(SKIPPED.name.lowercase(), items.getValue("variable NPM_TOKEN/per-member").action)
                val promoted = items.getValue("variable MY_KEY/mine (personal, ${memberA.memberId})")
                assertEquals(SKIPPED.name.lowercase(), promoted.action)
                val notes = items.getValue("skill notes")
                assertEquals("A skill named notes exists and was not imported from TeamCode.", notes.reason)
                val assignment = items.getValue("assignment NPM_TOKEN/${memberA.memberId}")
                assertEquals("Its variant was not imported.", assignment.reason)
            }
        }

    @Test
    fun aServerWithoutCatalogsSaysSo() =
        serverTest(listOf(SecretsModule, McpRelayModule, ImportModule), importOverrides()) { server ->
            with(server) {
                val request = TeamCodeImportRequest(skills = listOf(skill("review")), hooks = listOf(hook("guard")))
                assertEquals(
                    listOf(
                        line("skill", "review", SKIPPED, "This server keeps no skill catalog."),
                        line("hook", "guard", SKIPPED, "This server keeps no hook catalog."),
                    ),
                    report(request).lines(),
                )
            }
        }

    @Test
    fun aMalformedFileOrBindingRefusesTheRequest() = serverTest(modules, catalogOverrides()) { server ->
        with(server) {
            val badFile = TeamCodeImportRequest(
                skills = listOf(
                    TeamCodeSkillDto(
                        "s",
                        "review",
                        versions = listOf(
                            TeamCodeSkillVersionDto("1", listOf(TeamCodeArtifactFileDto("SKILL.md", "file", "%%"))),
                        ),
                    ),
                ),
            )
            assertEquals(HttpStatusCode.BadRequest, import(badFile).status)
            val badBinding = TeamCodeImportRequest(variables = listOf(TeamCodeVariableDto("v", "A_KEY", "n", "group")))
            assertEquals(HttpStatusCode.BadRequest, import(badBinding).status)
        }
    }

    @Test
    fun theValueCheckComparesWithoutRevealing() = serverTest(modules, catalogOverrides()) { server ->
        with(server) {
            report(catalog())
            val check = koin.get<EnvironmentValueCheck>()
            val variants = koin.get<ManageEnvironmentVariantsUseCase>().list(adminA, personal = false)
            val shared = variants.single { it.variable == "GITHUB_TOKEN" }.id
            val perMember = variants.single { it.variable == "NPM_TOKEN" }.id
            assertTrue(check.matches(orgA, shared, null, SecretValue(SHARED_VALUE)))
            assertFalse(check.matches(orgA, shared, null, SecretValue("other")))
            assertTrue(check.matches(orgA, perMember, memberA.memberId, SecretValue(LENA_VALUE)))
            assertFalse(check.matches(orgA, perMember, otherA.memberId, SecretValue(LENA_VALUE)))
            assertFalse(check.matches(orgA, Uuid.random(), null, SecretValue(SHARED_VALUE)))
        }
    }

    private companion object {
        val modules = listOf(SecretsModule, McpRelayModule, SkillsModule, HooksModule, ImportModule)

        /** The import's overrides, and the runners the skill and hook routes ask: none here, no runner calls. */
        fun catalogOverrides(): Module = module {
            includes(importOverrides())
            single<CheckRunnerTokenUseCase> {
                object : CheckRunnerTokenUseCase {
                    override suspend fun invoke(token: String) = throw RunnerUnauthenticatedException()
                }
            }
            single<AuthenticateRunnerUseCase> {
                object : AuthenticateRunnerUseCase {
                    override suspend fun invoke(token: String, sessionId: Uuid, epoch: Long): CloudSession =
                        throw RunnerUnauthenticatedException()
                }
            }
        }
        val orgA = Importing.orgA
        const val SHARED_VALUE = "ghp-shared-value-1"
        const val LENA_VALUE = "npm-lena-value-1"
        const val PERSONAL_VALUE = "my-personal-value-1"
        const val UNKNOWN_DECISION = "TeamCode's decision maybe is neither allow nor deny."
        const val NO_OWNER = "Its owner has no Ostrom member."
        const val NO_VERSION = "TeamCode has no version of it to import."
        const val NOT_IMPORTED = "Its skill or hook was not imported."
        const val GHOST = "No Ostrom member is mapped to ghost."
        const val FOREIGN_VARIANT = "A variant of this variable and name exists and was not imported from TeamCode."

        @Suppress("FunctionName", "ktlint:standard:function-naming") // The module's message, by name.
        fun RESERVED(name: String) = "$name is reserved for the runtime, its loader or the agent's own connection."

        val principal = AuthenticatedPrincipal("${adminA.memberId}", PrincipalKind.USER, setOf(ALL_USER_SCOPES))
        val admin = CatalogCaller(
            orgA,
            "${adminA.memberId}",
            true,
            CredentialKind.CONSOLE,
            setOf(ALL_USER_SCOPES),
            null,
            null,
            principal,
        )
        val members = listOf(
            TeamCodeMemberDto("m1", "lena"),
            TeamCodeMemberDto("m2", "vera"),
            TeamCodeMemberDto("m3", "ghost"),
        )

        fun file(mode: String, text: String) = TeamCodeArtifactFileDto("SKILL.md", mode, b64(text))
        val memberMap = mapOf("m1" to "${memberA.memberId}", "m2" to "${otherA.memberId}")

        fun b64(text: String) = Base64.encode(text.encodeToByteArray())

        fun skillFiles(body: String) = listOf(ArtifactFile("SKILL.md", FileMode.FILE, "# $body".encodeToByteArray()))

        fun skill(
            name: String,
            ownerId: String? = null,
            retired: Boolean = false,
            versions: List<TeamCodeSkillVersionDto> = listOf(
                TeamCodeSkillVersionDto("1", listOf(TeamCodeArtifactFileDto("SKILL.md", "file", b64("# $name")))),
            ),
            decisions: List<TeamCodeDecisionDto> = emptyList(),
        ) = TeamCodeSkillDto("s-$name", name, ownerId, retired = retired, versions = versions, decisions = decisions)

        fun hook(
            name: String,
            events: List<String> = listOf("PreToolUse"),
            versions: List<TeamCodeHookVersionDto>? = null,
            decisions: List<TeamCodeDecisionDto> = emptyList(),
        ) = TeamCodeHookDto(
            "h-$name",
            name,
            versions = versions ?: listOf(
                TeamCodeHookVersionDto(
                    "1",
                    events,
                    "Bash",
                    "run.sh",
                    10,
                    listOf(TeamCodeArtifactFileDto("run.sh", "executable", b64("#!/bin/sh\nexit 0\n"))),
                ),
            ),
            decisions = decisions,
        )

        @Suppress("LongParameterList") // One knob per change a test makes in TeamCode.
        fun catalog(
            shared: String = SHARED_VALUE,
            lenaValue: String = LENA_VALUE,
            personalValue: String = PERSONAL_VALUE,
            variantName: String = "org",
            skillPin: String? = "1.1.0",
            skillAudience: Boolean = true,
            hookPin: String? = "2",
            hookAudience: Boolean = false,
            extraSkillVersion: Boolean = false,
            lenaDecision: String = "allow",
            hookOmitted: Int = 0,
            lenaGithub: String = "v-gh",
        ) = TeamCodeImportRequest(
            members = members,
            variables = listOf(
                TeamCodeVariableDto("v-gh", "GITHUB_TOKEN", variantName, "shared", value = shared),
                TeamCodeVariableDto("v-npm", "NPM_TOKEN", "per-member", "perMember"),
                TeamCodeVariableDto("v-mine", "MY_KEY", "mine", "shared", ownerId = "m1", value = personalValue),
            ) + listOfNotNull(
                TeamCodeVariableDto("v-alt", "GITHUB_TOKEN", "alt", "shared", value = "ghp-alt-value-1")
                    .takeIf { lenaGithub == "v-alt" },
            ),
            variableAssignments = listOf(
                TeamCodeVariableAssignmentDto("m1", lenaGithub, "bulk"),
                TeamCodeVariableAssignmentDto("m1", "v-npm", "direct", lenaValue),
                TeamCodeVariableAssignmentDto("m1", "v-mine", "direct"),
            ),
            variableExclusions = listOf(TeamCodeVariableExclusionDto("m2", "GITHUB_TOKEN")),
            skills = listOf(
                TeamCodeSkillDto(
                    "s-review",
                    "review",
                    allMembers = skillAudience,
                    pinnedVersion = skillPin,
                    versions = listOfNotNull(
                        TeamCodeSkillVersionDto("1.0.0", listOf(file("file", "# v1"))),
                        TeamCodeSkillVersionDto("1.1.0", listOf(file("file", "# v1.1"))),
                        TeamCodeSkillVersionDto("1.2.0", listOf(file("file", "# v1.2")))
                            .takeIf { extraSkillVersion },
                    ),
                    omittedVersions = if (extraSkillVersion) 1 else 0,
                    decisions = listOf(TeamCodeDecisionDto("m2", "deny"), TeamCodeDecisionDto("m1", lenaDecision)),
                ),
                TeamCodeSkillDto(
                    "s-notes",
                    "notes",
                    ownerId = "m1",
                    pinnedVersion = "0.1.0",
                    versions = listOf(
                        TeamCodeSkillVersionDto("0.1.0", listOf(file("file", "# notes"))),
                    ),
                    decisions = listOf(TeamCodeDecisionDto("m2", "allow")),
                ),
            ),
            hooks = listOf(
                hook("guard", decisions = listOf(TeamCodeDecisionDto("m1", "allow"))).let { hook ->
                    val first = hook.versions.single()
                    hook.copy(
                        pinnedVersion = hookPin,
                        allMembers = hookAudience,
                        omittedVersions = hookOmitted,
                        versions = listOf(first, first.copy(version = "2", matcher = "Edit")),
                    )
                },
            ),
            memberMap = memberMap,
        )

        /** The lines of [catalog]'s import, each [writes]. */
        fun expected(writes: ImportAction) =
            listOf(
                line("variable", "GITHUB_TOKEN/org", writes),
                line("variable", "NPM_TOKEN/per-member", writes),
                line("variable", "MY_KEY/mine (personal, ${memberA.memberId})", writes),
                line("exclusion", "GITHUB_TOKEN/${otherA.memberId}", writes),
                line("assignment", "GITHUB_TOKEN/${memberA.memberId}", writes),
                line("assignment", "NPM_TOKEN/${memberA.memberId}", writes),
                line("skill", "review", writes),
                line("decision", "review/${otherA.memberId}", writes),
                line("decision", "review/${memberA.memberId}", writes),
                line("skill", "notes (personal, ${memberA.memberId})", writes),
                line("hook", "guard", writes),
                line("decision", "guard/${memberA.memberId}", writes),
            )

        /** The report's lines but the MCP prefix's, which every run reports. */
        fun ImportReportDto.lines() = items.filter { it.kind != "setting" }
    }
}
