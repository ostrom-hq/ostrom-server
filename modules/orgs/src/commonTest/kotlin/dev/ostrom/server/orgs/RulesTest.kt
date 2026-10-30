package dev.ostrom.server.orgs

import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.orgs.domain.FreePlanSeats
import dev.ostrom.server.orgs.domain.InvalidOrgRequestException
import dev.ostrom.server.orgs.domain.canManage
import dev.ostrom.server.orgs.domain.describeClaim
import dev.ostrom.server.orgs.domain.freeClaimOf
import dev.ostrom.server.orgs.domain.freeSlug
import dev.ostrom.server.orgs.domain.slugOf
import dev.ostrom.server.orgs.domain.validInviteEmail
import dev.ostrom.server.orgs.domain.validOrgName
import dev.ostrom.server.orgs.domain.validSlug
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class RulesTest {
    @Test
    fun aChosenShortNameIsLowerCaseLettersDigitsAndSingleDashes() {
        assertEquals("acme-2", validSlug("acme-2"))
        listOf("a", "Acme", "acme--x", "-acme", "acme-", "acme_x", "x".repeat(40)).forEach {
            assertFailsWith<InvalidOrgRequestException>(it) { validSlug(it) }
        }
        assertEquals(
            "This short name is reserved.",
            assertFailsWith<InvalidOrgRequestException> { validSlug("settings") }.message,
        )
    }

    @Test
    fun aNameIsTrimmedAndBounded() {
        assertEquals("Acme", validOrgName("  Acme "))
        listOf("", "   ", "x".repeat(101), "Ac\nme").forEach {
            assertFailsWith<InvalidOrgRequestException>(it) { validOrgName(it) }
        }
    }

    @Test
    fun theShortNameComesFromTheName() {
        assertEquals("acme-inc", slugOf("ACME, Inc."))
        assertEquals("team", slugOf("!!"))
        assertEquals("team", slugOf("é"))
        assertEquals("team", slugOf("API"))
        assertEquals(39, slugOf("a".repeat(60)).length)
    }

    @Test
    fun aTakenShortNameGetsANumberThenARandomSuffix() = runTest {
        assertEquals("acme", freeSlug("acme") { false })
        assertEquals("acme-3", freeSlug("acme") { it == "acme" || it == "acme-2" })
        val random = freeSlug("a".repeat(39)) { true }
        assertEquals(39, random.length)
        assertTrue(Regex("a{30}-[0-9a-f]{8}").matches(random), random)
    }

    @Test
    fun theFreePlanIsHeldByTheCompanyDomainOrThePerson() {
        val user = Uuid.random()
        assertEquals("domain:acme.com", freeClaimOf(user, setOf("ada@gmail.com", "ada@Acme.com")))
        assertEquals("user:$user", freeClaimOf(user, setOf("ada@gmail.com")))
        assertEquals("user:$user", freeClaimOf(user, emptySet()))
        assertEquals("user:$user", freeClaimOf(user, setOf("broken@")))
        assertEquals("the domain acme.com", describeClaim("domain:acme.com"))
        assertEquals("this GitHub organization", describeClaim("github:42"))
        assertEquals("your account", describeClaim("user:$user"))
    }

    @Test
    fun ownersManageEveryoneButThemselvesAdminsManageTheOtherRoles() {
        assertFalse(canManage(OrgRole.OWNER, OrgRole.OWNER))
        assertTrue(canManage(OrgRole.OWNER, OrgRole.ADMIN))
        assertFalse(canManage(OrgRole.ADMIN, OrgRole.ADMIN))
        assertTrue(canManage(OrgRole.ADMIN, OrgRole.BILLING))
        assertFalse(canManage(OrgRole.MEMBER, OrgRole.APPROVER))
    }

    @Test
    fun invitedAddressesAreNormalized() {
        assertEquals("ada@acme.com", validInviteEmail(" Ada@Acme.com "))
        listOf("ada", "ada@acme", "a b@acme.com", "a@" + "x".repeat(250) + ".com").forEach {
            assertFailsWith<InvalidOrgRequestException>(it) { validInviteEmail(it) }
        }
    }

    @Test
    fun theFreePlanAllowsFivePeopleAndPaysNothing() = runTest {
        val org = Uuid.random()
        assertTrue(FreePlanSeats().allows(org, 5))
        assertFalse(FreePlanSeats().allows(org, 6))
        assertFalse(FreePlanSeats().isPaying(org))
    }
}
