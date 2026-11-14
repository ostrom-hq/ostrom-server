// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.receipts

import run.ostrom.server.kernel.errors.InvalidPageException
import run.ostrom.server.receipts.domain.models.ReceiptAudience
import run.ostrom.server.receipts.domain.models.ReceiptNotFoundException
import run.ostrom.server.receipts.domain.models.ReceiptQuery
import run.ostrom.server.receipts.domain.models.ReceiptReader
import run.ostrom.server.receipts.domain.usecases.ListReceiptsUseCaseImpl
import run.ostrom.server.receipts.domain.usecases.ReadReceiptUseCaseImpl
import run.ostrom.server.receipts.domain.usecases.SignReceiptUseCaseImpl
import run.ostrom.server.receipts.domain.usecases.VerifyReceiptUseCaseImpl
import run.ostrom.server.receipts.infrastructure.InstanceReceiptSigner
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A receipt about a session is its member's (TeamCode #238, parity TC-NEW-675a530-2): read by the session's person,
 * the organization's owners and administrators, and the approvers of the session's gates; anyone else is answered as
 * if it did not exist. A receipt about no session (a harness, a decision, a gate of a pull request's CI) stays the
 * organization's.
 */
class ReceiptReadersTest {
    private val receipts = FakeReceipts()
    private val instance = SeedSigner(hex(GoldenVectors.SEED_HEX))
    private val owners = FakeOwners(mapOf("s-lyes" to "lyes", "s-mia" to "mia"))
    private val sign = SignReceiptUseCaseImpl(receipts, InstanceReceiptSigner(instance), FixedClock(), owners)

    // Rev approves a gate of Lyes's session.
    private val readers = FakeReaders(approvals = mapOf("rev" to listOf("s-lyes")))
    private val access = access(receipts, readers)
    private val get = ReadReceiptUseCaseImpl(access)
    private val list = ListReceiptsUseCaseImpl(receipts, access)
    private val verify = VerifyReceiptUseCaseImpl(access, FakeKeys(listOf(instance.receiptKey())))

    private fun member(login: String?) = ReceiptReader(ORG_A, login)

    @Test
    fun eachReceiptNamesTheSessionItIsAboutAndItsPerson() = runTest {
        sign(ORG_A, draft("s-lyes", "session"))
        sign(ORG_A, draft("gate-1", session = "s-mia"))
        sign(ORG_A, draft("gate-2", session = "s-gone"))
        sign(ORG_A, draft("harness-1", "harness"))
        val stored = receipts.rows.map { Triple(it.subjectId, it.sessionId, it.member) }
        assertEquals(
            listOf(
                Triple("s-lyes", "s-lyes", "lyes"),
                Triple("gate-1", "s-mia", "mia"),
                Triple("gate-2", "s-gone", null),
                Triple("harness-1", null, null),
            ),
            stored,
            "A session receipt is about its subject; another names its session; a harness none",
        )
    }

    @Test
    fun aSessionsReceiptIsReadByItsPersonAndItsApproversOnlyNeverByARole() = runTest {
        // TeamCode #238: what a session did is its member's, whatever the role; an administrator reads its record only.
        val id = sign(ORG_A, draft("s-lyes", "session")).text("id")
        assertEquals(id, get(member("lyes"), id).text("id"), "Its person")
        assertFailsWith<ReceiptNotFoundException>("An owner or an administrator") { get(admin(ORG_A), id) }
        assertEquals(id, get(member("rev"), id).text("id"), "An approver of its gates")
        assertFailsWith<ReceiptNotFoundException>("Another member") { get(member("mia"), id) }
        assertFailsWith<ReceiptNotFoundException>("A person whose login is unknown") { get(member(null), id) }
        assertFailsWith<ReceiptNotFoundException>("Another organization") { get(admin(ORG_B), id) }
        assertEquals(
            listOf("approves root s-lyes", "approves rev s-lyes", "approves mia s-lyes"),
            readers.asked,
            "Asked only of someone who is not its person",
        )
        assertFailsWith<ReceiptNotFoundException>("Nor verified by another member") { verify(member("mia"), id) }
        assertEquals(true, verify(member("rev"), id).ok)
    }

    @Test
    fun aReceiptAboutNoSessionIsTheOrganizations() = runTest {
        val id = sign(ORG_A, draft("harness-1", "harness")).text("id")
        assertEquals(id, get(member("mia"), id).text("id"))
        assertEquals(id, get(member(null), id).text("id"))
        assertEquals(emptyList(), readers.asked)
    }

    @Test
    fun aMemberListsTheReceiptsTheyRead() = runTest {
        val lyes = sign(ORG_A, draft("s-lyes", "session")).text("id")
        val mia = sign(ORG_A, draft("s-mia", "session")).text("id")
        val harness = sign(ORG_A, draft("harness-1", "harness")).text("id")
        assertEquals(listOf(harness), list(admin(ORG_A), ReceiptQuery()).map { it.id }, "No role lists them all")
        assertEquals(ReceiptAudience("root", emptyList()), receipts.lastQuery?.audience)
        assertEquals(listOf(harness, mia), list(member("mia"), ReceiptQuery()).map { it.id })
        assertEquals(ReceiptAudience("mia", emptyList()), receipts.lastQuery?.audience)
        assertEquals(listOf(harness, lyes), list(member("rev"), ReceiptQuery()).map { it.id })
        assertEquals(ReceiptAudience("rev", listOf("s-lyes")), receipts.lastQuery?.audience)
        readers.asked.clear()
        assertEquals(listOf(harness), list(member(null), ReceiptQuery()).map { it.id })
        assertEquals(ReceiptAudience(null, emptyList()), receipts.lastQuery?.audience)
        assertEquals(emptyList(), readers.asked, "Nobody to ask about")
    }

    @Test
    fun aCursorNamingAReceiptTheMemberDoesNotReadIsRefusedBeforeAnyPage() = runTest {
        val lyes = sign(ORG_A, draft("s-lyes", "session")).text("id")
        val mia = sign(ORG_A, draft("s-mia", "session")).text("id")
        assertFailsWith<InvalidPageException> { list(member("mia"), ReceiptQuery(before = lyes)) }
        assertNull(receipts.lastQuery, "No page was read")
        assertFailsWith<InvalidPageException> { list(member("mia"), ReceiptQuery(before = "rcpt_forged")) }
        assertEquals(emptyList(), list(member("mia"), ReceiptQuery(before = mia)))
        assertEquals(emptyList(), list(member("rev"), ReceiptQuery(before = lyes)), "An approver's cursor")
        // An administrator's cursor too: another member's receipt is one they do not read.
        assertFailsWith<InvalidPageException> { list(admin(ORG_A), ReceiptQuery(before = mia)) }
        assertFailsWith<InvalidPageException> { list(admin(ORG_A), ReceiptQuery(before = "rcpt_forged")) }
    }
}
