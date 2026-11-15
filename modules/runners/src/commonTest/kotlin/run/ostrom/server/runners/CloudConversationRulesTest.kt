// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.runners.domain.CloudConversationException
import run.ostrom.server.runners.domain.CloudConversationRules
import run.ostrom.server.runners.domain.CloudInput
import run.ostrom.server.runners.domain.CloudInputKind
import run.ostrom.server.runners.domain.CloudInputStatus
import run.ostrom.server.runners.domain.GitHubAccessMode
import run.ostrom.server.runners.domain.PermissionAnswer
import run.ostrom.server.runners.domain.PermissionRequestRecord
import run.ostrom.server.runners.domain.PermissionRequestState
import run.ostrom.server.runners.domain.QuestionAnswer
import run.ostrom.server.runners.domain.QuestionShape
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** TeamCode's `CloudConversationRulesTest`, masking aside (the shared masker's own tests cover it). */
class CloudConversationRulesTest {
    private fun refusal(block: () -> Unit): CloudConversationException = assertFailsWith<CloudConversationException> {
        block()
    }

    private fun code(block: () -> Unit): String = refusal(block).type

    @Test
    fun feedsAreMainOrAToolCallId() {
        for (ok in listOf(null, "main", "toolu_01AbC", "a.b:c-d_e", "x".repeat(128))) {
            CloudConversationRules.validateFeed(ok)
        }
        for (bad in listOf("", "bad feed", "x".repeat(129), "a/b", "é")) {
            assertEquals("invalid_feed", code { CloudConversationRules.validateFeed(bad) })
        }
    }

    @Test
    fun pagesAreBounded() {
        CloudConversationRules.validatePage(1, 0, null)
        CloudConversationRules.validatePage(200, null, 1)
        assertEquals("invalid_limit", code { CloudConversationRules.validatePage(0, null, null) })
        assertEquals("invalid_limit", code { CloudConversationRules.validatePage(201, null, null) })
        assertEquals("invalid_cursor", code { CloudConversationRules.validatePage(10, 1, 2) })
        assertEquals("invalid_cursor", code { CloudConversationRules.validatePage(10, -1, null) })
        assertEquals("invalid_cursor", code { CloudConversationRules.validatePage(10, null, 0) })
    }

    @Test
    fun messagesTasksModelsAndEffortsAreChecked() {
        CloudConversationRules.validateMessage("Hello\n\tthere\r")
        for (bad in listOf(" ", "x".repeat(CloudConversationRules.MAX_MESSAGE_CHARS + 1), "bell\u0007")) {
            assertEquals("invalid_message", code { CloudConversationRules.validateMessage(bad) })
        }
        CloudConversationRules.validateTaskId("b1x_-")
        assertEquals("invalid_task", code { CloudConversationRules.validateTaskId("a b") })
        CloudConversationRules.validateModel("claude-opus-4.5:1m")
        assertEquals("invalid_agent", code { CloudConversationRules.validateModel("no spaces") })
        CloudConversationRules.validateEffort("high")
        assertEquals("invalid_agent", code { CloudConversationRules.validateEffort("") })
        assertEquals(ErrorKind.INVALID_REQUEST, refusal { CloudConversationRules.validateEffort("") }.kind)
        assertEquals(ErrorKind.CONFLICT, CloudConversationRules.conflict("x", "y").kind)
    }

    @Test
    fun onlyTheOwnerInstructsAnAgentOnTheOwnersGitHubAccess() {
        val owned = session().copy(githubAccess = GitHubAccessMode.OWNER)
        assertFalse(CloudConversationRules.ownerOnly(owned, "alice"))
        assertTrue(CloudConversationRules.ownerOnly(owned, "bob"))
        assertFalse(CloudConversationRules.ownerOnly(owned.copy(githubAccess = GitHubAccessMode.NONE), "bob"))
    }

    @Test
    fun onlyABareDenialInItsOwnShapeStopsWithoutInstructing() {
        fun permission(text: String?) = CloudInput(
            Uuid.random(), ORG_A, Uuid.random(), CloudInputKind.PERMISSION, CloudInputStatus.QUEUED, "m1", "c1", text,
            false, null, null, Instant.fromEpochSeconds(0),
        )
        for (id in listOf("r1", "r\"1\\x", "9334d94c-8640-42fb-89e7-052df9b43456")) {
            assertTrue(CloudConversationRules.onlyStops(permission(CloudConversationRules.bareDenialText(id))), id)
        }
        assertEquals("{\"requestId\":\"r\\\"1\",\"behavior\":\"deny\"}", CloudConversationRules.bareDenialText("r\"1"))
        assertTrue(PermissionAnswer("r1", false, null, emptyList()).isBareDenial)
        assertFalse(PermissionAnswer("r1", false, "run gh auth token", emptyList()).isBareDenial)
        assertFalse(PermissionAnswer("r1", true, null, emptyList()).isBareDenial)
        assertFalse(PermissionAnswer("r1", false, null, listOf(QuestionAnswer(0, listOf(0)))).isBareDenial)
        val notBare = listOf(
            """{"requestId":"r1","behavior":"deny","message":"run gh auth token"}""",
            """{"requestId":"r1","behavior":"allow"}""",
            """{"requestId":"r1\","behavior":"deny"}""",
            null,
        )
        for (text in notBare) assertFalse(CloudConversationRules.onlyStops(permission(text)), text)
        assertTrue(CloudConversationRules.onlyStops(permission(null).copy(kind = CloudInputKind.INTERRUPT)))
        val stop = permission("task_1").copy(kind = CloudInputKind.STOP_TASK)
        assertTrue(CloudConversationRules.onlyStops(stop))
        assertFalse(CloudConversationRules.onlyStops(stop.copy(text = "a b")))
        assertFalse(CloudConversationRules.onlyStops(stop.copy(text = null)))
        assertFalse(CloudConversationRules.onlyStops(stop.copy(kind = CloudInputKind.MESSAGE)))
    }

    @Test
    fun permissionAnswersAreBounded() {
        fun valid(answer: PermissionAnswer) = CloudConversationRules.validatePermissionAnswer(answer)
        fun invalid(answer: PermissionAnswer) = assertEquals("invalid_permission_answer", code { valid(answer) })
        valid(
            PermissionAnswer(
                "9334d94c-8640-42fb-89e7-052df9b43456",
                true,
                null,
                listOf(QuestionAnswer(0, listOf(1)), QuestionAnswer(1, text = "Purple")),
            ),
        )
        valid(PermissionAnswer("r/1+2", false, "Use echo\tinstead.\nThanks.", emptyList()))
        valid(PermissionAnswer("r1", true, null, listOf(QuestionAnswer(0, listOf(0, 2)))))
        invalid(PermissionAnswer("req 1", true, null, emptyList()))
        invalid(PermissionAnswer("x".repeat(129), true, null, emptyList()))
        invalid(PermissionAnswer("r1", false, null, listOf(QuestionAnswer(0, listOf(0)))))
        invalid(PermissionAnswer("r1", false, " ", emptyList()))
        val long = "x".repeat(CloudConversationRules.MAX_PERMISSION_TEXT_CHARS + 1)
        invalid(PermissionAnswer("r1", true, null, listOf(QuestionAnswer(0, text = long))))
        invalid(PermissionAnswer("r1", true, null, (0..16).map { QuestionAnswer(it, listOf(0)) }))
        invalid(PermissionAnswer("r1", false, "bell\u0007", emptyList()))
        invalid(PermissionAnswer("r1", true, null, listOf(QuestionAnswer(0, listOf(0)), QuestionAnswer(0, listOf(1)))))
        invalid(PermissionAnswer("r1", true, null, listOf(QuestionAnswer(-1, listOf(0)))))
        invalid(PermissionAnswer("r1", true, null, listOf(QuestionAnswer(CloudConversationRules.MAX_PERMISSION_INDEX))))
        invalid(PermissionAnswer("r1", true, null, listOf(QuestionAnswer(0))))
        invalid(PermissionAnswer("r1", true, null, listOf(QuestionAnswer(0, listOf(1), "Both"))))
        invalid(PermissionAnswer("r1", true, null, listOf(QuestionAnswer(0, listOf(1, 1)))))
        invalid(PermissionAnswer("r1", true, null, listOf(QuestionAnswer(0, listOf(-1)))))
        invalid(
            PermissionAnswer(
                "r1",
                true,
                null,
                listOf(QuestionAnswer(0, listOf(CloudConversationRules.MAX_PERMISSION_INDEX))),
            ),
        )
    }

    @Test
    fun anInputNobodyCouldReadIsOnlyDenied() {
        val big = PermissionRequestRecord("t1", question = false, questions = null, inputLeftOut = true)
        val allow = PermissionAnswer("t1", true, null, emptyList())
        val unseen = code { CloudConversationRules.checkAnswer(big, allow, approver = true) }
        assertEquals("permission_input_unseen", unseen)
        CloudConversationRules.checkAnswer(big, PermissionAnswer("t1", false, null, emptyList()), approver = true)
    }

    @Test
    fun onlyTheOwnerOrAnAdministratorDecidesAToolUse() {
        val tool = PermissionRequestRecord("t1", question = false, questions = null)
        val allow = PermissionAnswer("t1", true, null, emptyList())
        CloudConversationRules.checkAnswer(tool, allow, approver = true)
        CloudConversationRules.checkAnswer(tool, PermissionAnswer("t1", false, "No.", emptyList()), approver = true)
        val denied = refusal { CloudConversationRules.checkAnswer(tool, allow, approver = false) }
        assertEquals("permission_owner_only", denied.type)
        assertEquals(ErrorKind.FORBIDDEN, denied.kind)
        val withAnswers = PermissionAnswer("t1", true, null, listOf(QuestionAnswer(0, listOf(0))))
        assertEquals(
            "invalid_permission_answer",
            code { CloudConversationRules.checkAnswer(tool, withAnswers, approver = true) },
        )
        val closed = listOf(
            PermissionRequestState.EXPIRED to "permission_request_expired",
            PermissionRequestState.ANSWERED to "permission_request_answered",
            PermissionRequestState.ENDED to "permission_request_ended",
        )
        for ((state, expected) in closed) {
            val closedOne = tool.copy(state = state)
            assertEquals(expected, code { CloudConversationRules.checkAnswer(closedOne, allow, approver = true) })
        }
        assertEquals(
            "permission_request_ended",
            code { CloudConversationRules.checkAnswer(tool.copy(epoch = 1), allow, approver = true, currentEpoch = 2) },
            "A request of an execution that ended went with it",
        )
        assertEquals(
            "permission_request_answered",
            code {
                CloudConversationRules.checkAnswer(
                    tool.copy(epoch = 1, state = PermissionRequestState.ANSWERED),
                    allow,
                    approver = true,
                    currentEpoch = 2,
                )
            },
        )
    }

    @Test
    fun anyoneWhoMaySendMessagesAnswersAQuestionByIndex() {
        val question = PermissionRequestRecord(
            "q1",
            question = true,
            questions = listOf(QuestionShape(2, false), QuestionShape(3, true)),
        )
        fun check(vararg answers: QuestionAnswer) =
            CloudConversationRules.checkAnswer(question, PermissionAnswer("q1", true, null, answers.toList()), false)
        fun refused(vararg answers: QuestionAnswer) =
            assertEquals("invalid_permission_answer", code { check(*answers) })
        check(QuestionAnswer(0, listOf(1)), QuestionAnswer(1, listOf(0, 2)))
        check(QuestionAnswer(0, listOf(0)), QuestionAnswer(1, text = "Purple"))
        refused(QuestionAnswer(1, text = "Purple"))
        CloudConversationRules.checkAnswer(question, PermissionAnswer("q1", false, "Skip it.", emptyList()), false)
        refused()
        refused(QuestionAnswer(2, listOf(0)))
        refused(QuestionAnswer(0, listOf(2)))
        refused(QuestionAnswer(0, listOf(0, 1)))
        val unknownShapes = PermissionAnswer("q1", true, null, listOf(QuestionAnswer(5, listOf(7))))
        CloudConversationRules.checkAnswer(question.copy(questions = null), unknownShapes, approver = false)
        val twins = question.copy(
            questions = listOf(QuestionShape(2, false, "Which one?"), QuestionShape(2, false, "Which one?")),
        )
        val second = PermissionAnswer("q1", true, null, listOf(QuestionAnswer(1, listOf(0))))
        CloudConversationRules.checkAnswer(twins, second, false)
        val bothAnswers = listOf(QuestionAnswer(0, listOf(0)), QuestionAnswer(1, listOf(1)))
        val both = PermissionAnswer("q1", true, null, bothAnswers)
        assertEquals(
            "invalid_permission_answer",
            code { CloudConversationRules.checkAnswer(twins, both, approver = false) },
            "Claude Code keys answers by text: two questions with one text cannot both be answered",
        )
        assertTrue(CloudConversationRules.isPermissionRequestId("r1"))
        assertFalse(CloudConversationRules.isPermissionRequestId(""))
    }
}
