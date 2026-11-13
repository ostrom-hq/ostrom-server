// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules

import run.ostrom.server.rules.domain.services.BuildFacts
import run.ostrom.server.rules.domain.services.CiEvent
import run.ostrom.server.rules.domain.services.CiFacts
import run.ostrom.server.rules.domain.services.CiFile
import run.ostrom.server.rules.domain.services.CiJob
import run.ostrom.server.rules.domain.services.CiNotice
import run.ostrom.server.rules.domain.services.FrontMatter
import run.ostrom.server.rules.domain.services.InstructionList
import run.ostrom.server.rules.domain.services.ListItem
import run.ostrom.server.rules.domain.services.MarkdownFacts
import run.ostrom.server.rules.domain.services.ProcessCommands
import run.ostrom.server.rules.domain.services.ReleaseFacts
import run.ostrom.server.rules.domain.services.ReleaseTarget
import run.ostrom.server.rules.domain.services.YamlOutline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The line of a text that holds a fragment, from 1. */
internal fun lineOf(text: String, fragment: String): Int = text.lines().indexOfFirst { fragment in it } + 1

class ProcessReadersTest {
    private val github = """
        - top: item
        name: CI
        "on":
          pull_request:
          push:
            tags: ['v*']
        jobs:
          - stray
          - keyed: item
          test:
            name: Unit tests
            runs-on: ubuntu-latest
            env:
              TOKEN: ghp_abcdefghijklmnopqrstuvwxyz0123
            steps:
            - uses: actions/checkout@v4
              with:
                token: run-this-never
            - plain
            - run: ./gradlew check
            - uses:
            - name: Lint
              run: |
                # comment
                npm ci
                npm run lint \
                  --fix
                export API_KEY=abc
                curl -H "Authorization: Bearer abcdefghijkl" x
                echo ${'$'}{{ secrets.X }}
          publish:
            name: ${'$'}{{ matrix.os }}
            if: startsWith(github.ref, 'refs/tags/')
            steps:
              - run: ./gradlew publish
              - uses: slackapi/slack-github-action@v1
              - uses: Ilshidur/action-discord@master
          announce:
            name:
            if: github.event_name == 'release'
            uses: ./.github/workflows/announce.yml
          docs:
            if: github.ref == 'refs/heads/main'
            steps:
              - run: make docs
          pushed:
            if: github.event_name == 'push'
          plainline
        """.trimIndent()

    @Test
    fun theYamlOutlineReadsKeysValuesListsAndBlocks() {
        val text = """
            ---
            # a comment
            "on": push # why
            'quoted
            "bare" value
            plain
            list:
            - a
            -
            - key: one
              more: two
            flow: [a, "b", ]
            block: |
              first

              second
            items:
              - |
                inside
              - "q #x"
            ...
        """.trimIndent()
        val top = YamlOutline.parse(text)
        assertEquals(listOf("on", null, null, null, "list", "flow", "block", "items"), top.map { it.key })
        assertEquals(listOf("push", "'quoted", "\"bare\" value", "plain"), top.take(4).map { it.value })
        val list = top[4]
        assertEquals(listOf(true, true, true), list.children.map { it.item })
        assertNull(list.children[1].value, "A lone dash holds nothing")
        val keyed = list.children[2]
        assertEquals(listOf("key", "more"), keyed.fields().map { it.key })
        assertEquals(listOf("more"), keyed.children.map { it.key })
        assertEquals(emptyList(), list.children[0].fields(), "A plain item opens no mapping")
        assertEquals(listOf("a", "a"), list.scalars().map { it.second }.take(1) + list.children[0].value)
        assertEquals(listOf("a", "b"), top[5].scalars().map { it.second })
        val block = top[6]
        assertNull(block.value)
        assertEquals(listOf(lineOf(text, "first") to "first", lineOf(text, "second") to "second"), block.block)
        assertEquals(listOf("first", "second"), block.scalars().map { it.second })
        assertEquals(listOf("inside", "q #x"), top[7].scalars().map { it.second })
        assertEquals(lineOf(text, "items:"), top[7].line)
        assertEquals(null, top[7].child("missing"))
        assertEquals(listOf("whole"), YamlOutline.flow("whole"))
        assertEquals(listOf("[open"), YamlOutline.flow("[open"))
        assertEquals(list.children, list.fields(), "A key's fields are its children")
    }

    @Test
    fun aListMaySitAtItsKeysIndentation() {
        val top = YamlOutline.parse("steps:\n- run: a\n- run: b\nnext: c")
        assertEquals(listOf("steps", "next"), top.map { it.key })
        assertEquals(listOf("a", "b"), top[0].children.map { it.value })
    }

    @Test
    fun aHostileDepthNeverExhaustsTheStack() {
        val deep = (0 until 2_000).joinToString("\n") { " ".repeat(it) + "k$it:" }
        val top = YamlOutline.parse(deep)
        assertEquals(1, top.size)
    }

    @Test
    fun onlyCleanCommandsWithoutSecretsAreSuggested() {
        assertEquals("./gradlew check", ProcessCommands.safe("  ./gradlew check "))
        val refused = listOf(
            "",
            "# a comment",
            "x".repeat(ProcessCommands.MAX_COMMAND + 1),
            "export API_KEY=abc",
            "deploy --token=abc",
            "echo \${{ secrets.TOKEN }}",
            "echo \$GH secrets.TOKEN",
            "echo $(cat key)",
            "echo a\u202Eb",
            "echo `id`",
            "curl -H 'Authorization: Bearer abcdefghijkl'",
            "git clone https://user:pass@example.com/x",
            "curl -u admin:topsecret https://x",
            "curl --user admin:topsecret https://x",
            "use ghp_abcdefghijklmnopqrstuvwxyz0123",
        )
        for (line in refused) assertNull(ProcessCommands.safe(line), line)
        assertEquals(
            listOf(1 to "npm run lint --fix", 3 to "npm test", 4 to "make all"),
            ProcessCommands.commands(
                listOf(1 to "npm run lint \\", 2 to "  --fix", 3 to "npm test", 4 to "make \\", 5 to "all \\"),
            ).map { it.first to it.second.trim() },
        )
        assertEquals(emptyList(), ProcessCommands.commands(listOf(1 to "A=1 make")))
    }

    @Test
    fun aGitHubWorkflowGivesItsPullRequestAndReleaseJobs() {
        val text = github
        val file = CiFacts.read(".github/workflows/ci.yml", text)
        val path = ".github/workflows/ci.yml"
        assertEquals(
            listOf(
                CiJob(
                    path,
                    lineOf(text, "  test:"),
                    "Unit tests",
                    CiEvent.PULL_REQUEST,
                    listOf(
                        lineOf(text, "./gradlew check") to "./gradlew check",
                        lineOf(text, "npm ci") to "npm ci",
                        lineOf(text, "npm run lint") to "npm run lint --fix",
                    ),
                ),
                CiJob(
                    path,
                    lineOf(text, "publish:"),
                    "publish",
                    CiEvent.RELEASE,
                    listOf(lineOf(text, "./gradlew publish") to "./gradlew publish"),
                ),
                CiJob(path, lineOf(text, "announce:"), "announce", CiEvent.RELEASE, emptyList()),
            ),
            file.jobs,
        )
        assertEquals(
            listOf(
                CiNotice(path, lineOf(text, "slackapi"), "Slack"),
                CiNotice(path, lineOf(text, "action-discord"), "Discord"),
            ),
            file.notices,
        )
        assertFalse(file.toString().contains("run-this-never"), "A with: input is never read")
        assertFalse(file.toString().contains("ghp_"), "An env: value is never read")
    }

    @Test
    fun aWorkflowsTriggerDecidesItsJobsWhenTheyNameNone() {
        val job = "jobs:\n  build:\n    steps:\n      - run: make\n"
        fun events(on: String) = CiFacts.read(".github/workflows/a.yaml", on + job).jobs.map { it.event }
        assertEquals(listOf(CiEvent.PULL_REQUEST), events("on: [push, pull_request]\n"))
        assertEquals(listOf(CiEvent.PULL_REQUEST), events("on:\n  - merge_group\n"))
        assertEquals(listOf(CiEvent.RELEASE), events("on: release\n"))
        assertEquals(listOf(CiEvent.RELEASE), events("on:\n  push:\n    tags:\n      - v*\n"))
        assertEquals(emptyList(), events("on:\n  push:\n    branches: [main]\n"))
        assertEquals(emptyList(), events("on: push\n"))
        assertEquals(emptyList(), events(""))
        assertEquals(CiFile(emptyList(), emptyList()), CiFacts.read(".github/workflows/a.yml", "on: pull_request\n"))
    }

    @Test
    fun aGitLabPipelineGivesItsMergeRequestAndTagJobs() {
        val text = """
            - stray
            - keyed: item
            stages: [test]
            variables:
              TOKEN: abc
            .template:
              script: echo hidden
            unit:
              script:
                - make test
                - |
                  ./gradlew check
                  echo PASSWORD=hunter2
            mr:
              rules:
                - if: ${'$'}CI_PIPELINE_SOURCE == "merge_request_event"
                - when: manual
                - if:
              script: npm test
            tagged:
              rules:
                - if: ${'$'}CI_COMMIT_TAG
              script: make tag
            release:
              only:
                - tags
              script: [make release]
            refs:
              only:
                refs: [tags]
              script: make refs
            requests:
              only: [merge_requests]
              script: make mr
            deploy:
              only: [main]
              script: make deploy
            nojob:
              image: alpine
        """.trimIndent()
        val jobs = CiFacts.read(CiFacts.GITLAB, text).jobs
        assertEquals(
            listOf(
                "unit" to CiEvent.PULL_REQUEST, "mr" to CiEvent.PULL_REQUEST, "tagged" to CiEvent.RELEASE,
                "release" to CiEvent.RELEASE, "refs" to CiEvent.RELEASE, "requests" to CiEvent.PULL_REQUEST,
            ),
            jobs.map { it.name to it.event },
        )
        assertEquals(listOf("make test", "./gradlew check"), jobs[0].commands.map { it.second })
        assertEquals(listOf(lineOf(text, "make release") to "make release"), jobs[3].commands)
        assertEquals(emptyList(), CiFacts.read(CiFacts.GITLAB, text).notices)
    }

    @Test
    fun onlyTheCiFilesItKnowsAreRead() {
        assertTrue(CiFacts.isCiFile(".github/workflows/ci.yml"))
        assertTrue(CiFacts.isCiFile(".github/workflows/ci.yaml"))
        assertTrue(CiFacts.isCiFile(".gitlab-ci.yml"))
        assertFalse(CiFacts.isCiFile(".github/workflows/sub/ci.yml"))
        assertFalse(CiFacts.isCiFile(".github/workflows/README.md"))
        assertFalse(CiFacts.isCiFile("ci.yml"))
    }

    @Test
    fun aHostileCiFileIsBounded() {
        val jobs = (0 until 10_000).joinToString("\n") { "  j$it:\n    steps:\n      - run: make t$it" }
        val file = CiFacts.read(".github/workflows/x.yml", "on: pull_request\njobs:\n$jobs")
        assertEquals(CiFacts.MAX_JOBS, file.jobs.size)
        val steps = (0 until 1_000).joinToString("\n") { "      - run: make s$it" }
        val one = CiFacts.read(".github/workflows/x.yml", "on: pull_request\njobs:\n  j:\n    steps:\n$steps")
        assertEquals(CiFacts.MAX_JOB_COMMANDS, one.jobs.single().commands.size)
    }

    @Test
    fun releaseScriptsAndTargetsAreFoundByTheirNames() {
        assertEquals(
            listOf(
                ReleaseTarget("scripts/release-notes.py", null, "./scripts/release-notes.py"),
                ReleaseTarget("scripts/release.sh", null, "./scripts/release.sh"),
            ),
            ReleaseFacts.scripts(
                listOf(
                    "scripts/release.sh", "scripts/release-notes.py", "scripts/x/release.sh", "scripts/build.sh",
                    "scripts/release me.sh",
                ),
            ),
        )
        val makefile = "all:\n\techo\nrelease: build\nrelease := 1\ndeploy-prod:\npublish:\npublish:\nreleasenotes:\n"
        assertEquals(
            listOf(
                ReleaseTarget("Makefile", 3, "make release"),
                ReleaseTarget("Makefile", 5, "make deploy-prod"),
                ReleaseTarget("Makefile", 6, "make publish"),
            ),
            ReleaseFacts.makefile(makefile),
        )
    }

    @Test
    fun releaseScriptsOfTheManifestAreRunByItsPackageManager() {
        val manifest = """
            {
              "name": "web",
              "scripts": {
                "test": "vitest",
                "release": "changeset publish",
                "deploy:prod": "x",
                "rel\u0065ase:beta": "y",
                "publish now": "z"
              }
            }
        """.trimIndent()
        assertEquals(
            listOf(
                ReleaseTarget("package.json", 5, "run release"),
                ReleaseTarget("package.json", 6, "run deploy:prod"),
                ReleaseTarget("package.json", null, "run release:beta"),
            ),
            ReleaseFacts.packageJson(manifest) { "run $it" },
        )
        assertEquals(emptyList(), ReleaseFacts.packageJson("{", ::error))
        assertEquals(emptyList(), ReleaseFacts.packageJson("[]", ::error))
        assertEquals(emptyList(), ReleaseFacts.packageJson("{\"scripts\": 1}", ::error))
    }

    @Test
    fun releaseLanesAreFoundUnderTheirPlatform() {
        val fastfile = """
            lane :release_all do
            end
            platform :ios do
              lane :test do
              end
              lane :beta do
              end
            end
            platform :android do
              lane :Deploy do
              end
            end
        """.trimIndent()
        assertEquals(
            listOf(
                ReleaseTarget("fastlane/Fastfile", 1, "fastlane release_all"),
                ReleaseTarget("fastlane/Fastfile", 6, "fastlane ios beta"),
                ReleaseTarget("fastlane/Fastfile", 10, "fastlane android Deploy"),
            ),
            ReleaseFacts.fastfile(fastfile),
        )
        val many = (0 until 10_000).joinToString("\n") { "lane :release$it do" }
        assertEquals(ReleaseFacts.MAX_TARGETS, ReleaseFacts.fastfile(many).size)
        val targets = (0 until 50).joinToString("\n") { "deploy-$it:" }
        assertEquals(ReleaseFacts.MAX_TARGETS, ReleaseFacts.makefile(targets).size)
    }

    @Test
    fun theirPackageManagerRunsTheScripts() {
        assertEquals("pnpm release", BuildFacts.runScript(listOf("package.json", "pnpm-lock.yaml"), "release"))
        assertEquals("npm run release", BuildFacts.runScript(listOf("package.json", "web/pnpm-lock.yaml"), "release"))
    }

    @Test
    fun aHeadingFollowedByANumberedListIsAProcess() {
        val text = """
            Intro
            1. before any heading
            2. still before
            # Release
            Follow these steps.

            1. Bump the version
               in gradle.properties
            ${"\t"}and a tab
               - a nested note

            2) Run `./gradlew check` and [the docs](https://x)
            3. **Tag** it
            After the list.
            4. not in the list
            ## One item
            1. alone
            ```
            # Not a heading
            1. not an item
            2. not an item
            ```
            <!-- 1. hidden -->
            <!--
            # Hidden heading
            1. hidden
            2. hidden
            -->
            ## Deploy ##
            1. Build
            2. Ship
        """.trimIndent()
        assertEquals(
            listOf(
                InstructionList(
                    "Release",
                    lineOf(text, "# Release"),
                    listOf(
                        ListItem(lineOf(text, "Bump"), "Bump the version"),
                        ListItem(lineOf(text, "2) Run"), "Run `./gradlew check` and [the docs](https://x)"),
                        ListItem(lineOf(text, "Tag"), "**Tag** it"),
                    ),
                ),
                InstructionList(
                    "Deploy",
                    lineOf(text, "## Deploy"),
                    listOf(ListItem(lineOf(text, "Build"), "Build"), ListItem(lineOf(text, "Ship"), "Ship")),
                ),
            ),
            MarkdownFacts.lists(text),
        )
    }

    @Test
    fun anItemsWordsAndCommandsAreReadAsMarkdownShowsThem() {
        assertEquals(
            "Run `./gradlew check` and the docs",
            MarkdownFacts.words("Run `./gradlew check` and [the docs](https://x)"),
        )
        assertEquals("Tag it", MarkdownFacts.words("**Tag** __it__"))
        assertEquals(listOf("./gradlew check", "b"), MarkdownFacts.codeSpans("Run ` ./gradlew check ` or `b`"))
    }

    @Test
    fun aHostileListIsBounded() {
        val items = (1..10_000).joinToString("\n") { "$it. step $it" }
        val list = MarkdownFacts.lists("# Steps\n$items").single()
        assertEquals(MarkdownFacts.MAX_ITEMS, list.items.size)
        val lists = (0 until 1_000).joinToString("\n") { "# H$it\n1. a\n2. b" }
        assertEquals(MarkdownFacts.MAX_LISTS, MarkdownFacts.lists(lists).size)
        val checks = (0 until 10_000).joinToString("\n") { "- [ ] c$it" }
        assertEquals(MarkdownFacts.MAX_ITEMS, MarkdownFacts.checklist(checks).size)
    }

    @Test
    fun aChecklistIsReadOutsideCommentsAndCode() {
        val text = "## Checklist\n- [ ] Tests added\n* [x] Docs\n<!-- - [ ] hidden -->\n```\n- [ ] code\n```\n" +
            "+ [X] Done\n- plain"
        assertEquals(
            listOf(ListItem(2, "Tests added"), ListItem(3, "Docs"), ListItem(8, "Done")),
            MarkdownFacts.checklist(text),
        )
    }

    @Test
    fun aSkillsFrontMatterNamesAndDescribesIt() {
        assertEquals(
            FrontMatter("release", "Ships a version of the app.", 3),
            MarkdownFacts.frontMatter(
                "---\nname: release\ndescription: >\n  Ships a version\n  of the app.\n---\n# Body",
            ),
        )
        val oneLine = MarkdownFacts.frontMatter("---\nname: x\ndescription: One line\n---")
        assertEquals(FrontMatter("x", "One line", 3), oneLine)
        assertEquals(FrontMatter(null, null, 3), MarkdownFacts.frontMatter("---\n- item\ndescription:\n---"))
        assertEquals(FrontMatter(null, null, 1), MarkdownFacts.frontMatter("---\nname: x\n"))
        assertEquals(FrontMatter(null, null, 1), MarkdownFacts.frontMatter("# No front matter\n---"))
    }
}
