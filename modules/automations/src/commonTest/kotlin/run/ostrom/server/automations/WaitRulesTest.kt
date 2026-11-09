// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.automations

import run.ostrom.server.automations.domain.CalendarDates
import run.ostrom.server.automations.domain.InvalidWaitAnswerException
import run.ostrom.server.automations.domain.InvalidWaitException
import run.ostrom.server.automations.domain.WaitAction
import run.ostrom.server.automations.domain.WaitField
import run.ostrom.server.automations.domain.WaitFieldType
import run.ostrom.server.automations.domain.WaitRef
import run.ostrom.server.automations.domain.WaitRules
import run.ostrom.server.automations.domain.WaitSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WaitRulesTest {
    private val spec = WaitSpec("Review the plan")

    private fun refused(change: WaitSpec): String = assertFailsWith<InvalidWaitException> {
        WaitRules.question(change)
    }.message

    private fun field(
        type: WaitFieldType,
        id: String = "f",
        options: List<String> = emptyList(),
    ) = WaitField(id, type, "Label", options = options)

    @Test
    fun aQuestionIsTrimmedAndGetsADoneAction() {
        val stored = WaitRules.question(spec.copy(title = "  Review  "))
        assertEquals("Review", stored.title)
        assertEquals(listOf(WaitRules.DONE), stored.actions)
        val actions = listOf(WaitAction("ok", "OK", style = "primary"), WaitAction("no", "No", style = null))
        assertEquals(actions, WaitRules.question(spec.copy(actions = actions)).actions)
        val full = spec.copy(
            body = "b".repeat(WaitRules.MAX_BODY),
            refs = listOf(
                WaitRef("artifact", id = "a-1", title = "t"),
                WaitRef("session", id = "s:1~r2"),
                WaitRef("url", url = "https://example.com/x"),
            ),
            fields = listOf(
                WaitField("text1", WaitFieldType.TEXT, "T", help = "h", required = true, maxLength = 10),
                WaitField("pick", WaitFieldType.CHOICE, "P", options = listOf("a"), multiple = true, allowOther = true),
                WaitField("note", WaitFieldType.INFO, "I"),
            ),
            actions = listOf(WaitAction("go", "Go", requires = listOf("text1", "pick"))),
        )
        assertEquals(full, WaitRules.question(full))
    }

    @Test
    fun malformedQuestionsAreRefused() {
        assertTrue("title" in refused(spec.copy(title = " ")))
        assertTrue("title" in refused(spec.copy(title = "x".repeat(WaitRules.MAX_TITLE + 1))))
        assertTrue("title" in refused(spec.copy(title = "a\u0007b")))
        assertTrue("body" in refused(spec.copy(body = "b".repeat(WaitRules.MAX_BODY + 1))))
        assertTrue("refs" in refused(spec.copy(refs = List(WaitRules.MAX_REFS + 1) { WaitRef("session", "s") })))
        assertTrue("A ref is" in refused(spec.copy(refs = listOf(WaitRef("file", "x")))))
        val longTitle = WaitRef("session", "s", title = "t".repeat(WaitRules.MAX_LABEL + 1))
        assertTrue("title" in refused(spec.copy(refs = listOf(longTitle))))
        assertTrue("https" in refused(spec.copy(refs = listOf(WaitRef("url", url = "http://example.com")))))
        assertTrue("https" in refused(spec.copy(refs = listOf(WaitRef("url")))))
        val longUrl = "https://e.com/" + "x".repeat(WaitRules.MAX_URL)
        assertTrue("https" in refused(spec.copy(refs = listOf(WaitRef("url", url = longUrl)))))
        assertTrue("https" in refused(spec.copy(refs = listOf(WaitRef("url", url = "https://e.com/a b")))))
        assertTrue("names its id" in refused(spec.copy(refs = listOf(WaitRef("artifact")))))
        assertTrue("names its id" in refused(spec.copy(refs = listOf(WaitRef("artifact", "a/b")))))
    }

    @Test
    fun malformedFieldsAreRefused() {
        fun refusedField(vararg fields: WaitField) = refused(spec.copy(fields = fields.toList()))
        val text = field(WaitFieldType.TEXT)
        val many = List(WaitRules.MAX_FIELDS + 1) { field(WaitFieldType.TEXT, "f$it") }
        assertTrue("fields" in refused(spec.copy(fields = many)))
        assertTrue("unique" in refusedField(text, text))
        assertTrue("field id" in refusedField(field(WaitFieldType.TEXT, "1x")))
        assertTrue("label" in refusedField(text.copy(label = " ")))
        assertTrue("label" in refusedField(text.copy(label = "l".repeat(WaitRules.MAX_LABEL + 1))))
        assertTrue("help" in refusedField(text.copy(help = "h".repeat(WaitRules.MAX_HELP + 1))))
        assertTrue("options" in refusedField(field(WaitFieldType.CHOICE)))
        assertTrue("options" in refusedField(field(WaitFieldType.TEXT, options = listOf("a"))))
        val tooMany = List(WaitRules.MAX_OPTIONS + 1) { "o$it" }
        assertTrue("options" in refusedField(field(WaitFieldType.CHOICE, options = tooMany)))
        assertTrue("unique" in refusedField(field(WaitFieldType.CHOICE, options = listOf("a", "a"))))
        assertTrue("unique" in refusedField(field(WaitFieldType.CHOICE, options = listOf(""))))
        val longOption = "o".repeat(WaitRules.MAX_LABEL + 1)
        assertTrue("unique" in refusedField(field(WaitFieldType.CHOICE, options = listOf(longOption))))
        assertTrue("only a choice" in refusedField(text.copy(multiple = true)))
        assertTrue("only a choice" in refusedField(text.copy(allowOther = true)))
        assertTrue("maxLength" in refusedField(field(WaitFieldType.NUMBER).copy(maxLength = 3)))
        assertTrue("maxLength" in refusedField(text.copy(maxLength = 0)))
        assertTrue("maxLength" in refusedField(text.copy(maxLength = WaitRules.MAX_TEXT + 1)))
        assertTrue("info" in refusedField(field(WaitFieldType.INFO).copy(required = true)))
    }

    @Test
    fun malformedActionsAreRefused() {
        fun refusedAction(vararg actions: WaitAction) =
            refused(spec.copy(fields = listOf(field(WaitFieldType.INFO, "note")), actions = actions.toList()))
        val ok = WaitAction("ok", "OK")
        assertTrue("actions" in refusedAction(*Array(WaitRules.MAX_ACTIONS + 1) { WaitAction("a$it", "A") }))
        assertTrue("unique" in refusedAction(ok, ok))
        assertTrue("action id" in refusedAction(WaitAction("-", "A")))
        assertTrue("label" in refusedAction(ok.copy(label = "")))
        assertTrue("label" in refusedAction(ok.copy(label = "l".repeat(WaitRules.MAX_LABEL + 1))))
        assertTrue("style" in refusedAction(ok.copy(style = "loud")))
        assertTrue("requires" in refusedAction(ok.copy(requires = listOf("missing"))))
        assertTrue("requires" in refusedAction(ok.copy(requires = listOf("note"))))
    }

    private val form = WaitSpec(
        title = "Form",
        fields = listOf(
            WaitField("text", WaitFieldType.TEXT, "Text", maxLength = 5),
            WaitField("long", WaitFieldType.TEXT, "Long"),
            WaitField("one", WaitFieldType.CHOICE, "One", options = listOf("a", "b")),
            WaitField("other", WaitFieldType.CHOICE, "Other", options = listOf("a"), allowOther = true),
            WaitField("many", WaitFieldType.CHOICE, "Many", options = listOf("a", "b"), multiple = true),
            WaitField("yes", WaitFieldType.CONFIRM, "Yes"),
            WaitField("count", WaitFieldType.NUMBER, "Count"),
            WaitField("day", WaitFieldType.DATE, "Day"),
            WaitField("note", WaitFieldType.INFO, "Note"),
            WaitField("must", WaitFieldType.TEXT, "Must", required = true),
        ),
        actions = listOf(WaitAction("approve", "Approve"), WaitAction("change", "Change", requires = listOf("long"))),
    )

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun answer(action: String = "approve", vararg values: Pair<String, String>) {
        val given = values.associate { it.first to json(it.second) }
        WaitRules.answer(form, action, mapOf("must" to JsonPrimitive("x")) + given)
    }

    private fun refusedAnswer(action: String = "approve", vararg values: Pair<String, String>): String =
        assertFailsWith<InvalidWaitAnswerException> { answer(action, *values) }.message

    @Test
    fun answersThatFitTheQuestionPass() {
        answer(
            "change",
            "text" to "\"abcde\"",
            "long" to "\"" + "l".repeat(WaitRules.DEFAULT_TEXT) + "\"",
            "one" to "\"b\"",
            "other" to "\"something else\"",
            "many" to "[\"a\",\"b\"]",
            "yes" to "false",
            "count" to "-2.5",
            "day" to "\"2024-02-29\"",
            "note" to "null",
        )
        answer("approve", "many" to "[]", "text" to "null", "other" to "\"a\"")
        WaitRules.answer(WaitRules.question(WaitSpec("Done?")), "done", emptyMap())
    }

    @Test
    fun answersThatDoNotFitAreRefused() {
        assertTrue("approve, change" in refusedAnswer("reject"))
        assertTrue("no field named ghost" in refusedAnswer("approve", "ghost" to "1"))
        assertTrue("at most 5" in refusedAnswer("approve", "text" to "\"abcdef\""))
        assertTrue("at most 5" in refusedAnswer("approve", "text" to "[1]"))
        assertTrue("at most 8000" in refusedAnswer("approve", "long" to "1"))
        assertTrue("one of its options" in refusedAnswer("approve", "one" to "\"c\""))
        assertTrue("one of its options" in refusedAnswer("approve", "one" to "1"))
        assertTrue("one of its options" in refusedAnswer("approve", "other" to "\"\""))
        val other = "\"" + "o".repeat(WaitRules.MAX_OTHER + 1) + "\""
        assertTrue("one of its options" in refusedAnswer("approve", "other" to other))
        assertTrue("a list" in refusedAnswer("approve", "many" to "\"a\""))
        assertTrue("a list" in refusedAnswer("approve", "many" to "[\"a\",\"b\",\"a\",\"b\"]"))
        assertTrue("a list" in refusedAnswer("approve", "many" to "[\"c\"]"))
        assertTrue("true or false" in refusedAnswer("approve", "yes" to "[true]"))
        assertTrue("true or false" in refusedAnswer("approve", "yes" to "\"true\""))
        assertTrue("true or false" in refusedAnswer("approve", "yes" to "1"))
        assertTrue("finite number" in refusedAnswer("approve", "count" to "{}"))
        assertTrue("finite number" in refusedAnswer("approve", "count" to "\"1\""))
        assertTrue("finite number" in refusedAnswer("approve", "count" to "true"))
        assertTrue("finite number" in refusedAnswer("approve", "count" to "1e400"))
        assertTrue("YYYY-MM-DD" in refusedAnswer("approve", "day" to "20240101"))
        assertTrue("YYYY-MM-DD" in refusedAnswer("approve", "day" to "\"2023-02-29\""))
        assertTrue("info field" in refusedAnswer("approve", "note" to "\"x\""))
        assertTrue("long must be filled" in refusedAnswer("change"))
        assertTrue("long must be filled" in refusedAnswer("change", "long" to "\" \""))
        assertTrue("must must be filled" in assertFailsWith<InvalidWaitAnswerException> {
            WaitRules.answer(form, "approve", mapOf("must" to JsonNull))
        }.message)
    }

    @Test
    fun aRequiredConfirmationIsFilledByFalseToo() {
        val yes = WaitField("yes", WaitFieldType.CONFIRM, "Y", required = true)
        val sure = WaitRules.question(WaitSpec("Sure?", fields = listOf(yes)))
        WaitRules.answer(sure, "done", mapOf("yes" to json("false")))
    }

    @Test
    fun aRequiredListIsFilledByOneOptionAtLeast() {
        val many = WaitField("many", WaitFieldType.CHOICE, "M", required = true, options = listOf("a"), multiple = true)
        val pick = WaitRules.question(WaitSpec("Pick", fields = listOf(many)))
        val empty = assertFailsWith<InvalidWaitAnswerException> {
            WaitRules.answer(pick, "done", mapOf("many" to json("[]")))
        }
        assertTrue("many must be filled" in empty.message)
        WaitRules.answer(pick, "done", mapOf("many" to json("[\"a\"]")))
    }

    @Test
    fun datesAreRealCalendarDates() {
        assertTrue(CalendarDates.isDate("2000-02-29"))
        assertTrue(CalendarDates.isDate("2026-01-31"))
        assertTrue(CalendarDates.isDate("2026-04-30"))
        assertFalse(CalendarDates.isDate("1900-02-29"))
        assertFalse(CalendarDates.isDate("2026-04-31"))
        assertFalse(CalendarDates.isDate("2026-13-01"))
        assertFalse(CalendarDates.isDate("2026-00-01"))
        assertFalse(CalendarDates.isDate("2026-01-00"))
        assertFalse(CalendarDates.isDate("26-01-01"))
        listOf("06", "09", "11").forEach { assertFalse(CalendarDates.isDate("2026-$it-31")) }
    }
}
