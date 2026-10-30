// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.artifacts

import dev.ostrom.server.artifacts.domain.ArtifactAccess
import dev.ostrom.server.artifacts.domain.ArtifactConflictException
import dev.ostrom.server.artifacts.domain.ArtifactLocations
import dev.ostrom.server.artifacts.domain.ArtifactNotFoundException
import dev.ostrom.server.artifacts.domain.ArtifactNotWritableException
import dev.ostrom.server.artifacts.domain.ArtifactPagesUnavailableException
import dev.ostrom.server.artifacts.domain.ContentDigest
import dev.ostrom.server.artifacts.domain.InvalidArtifactRequestException
import dev.ostrom.server.artifacts.domain.ManageArtifactUseCaseImpl
import dev.ostrom.server.artifacts.domain.PublishArtifactUseCaseImpl
import dev.ostrom.server.artifacts.domain.ReadArtifactsUseCaseImpl
import dev.ostrom.server.artifacts.domain.UploadedFile
import dev.ostrom.server.artifacts.domain.ViewArtifactUseCaseImpl
import dev.ostrom.server.artifacts.fakes.CONTENT_ORIGIN
import dev.ostrom.server.artifacts.fakes.InMemoryArtifacts
import dev.ostrom.server.artifacts.fakes.TestClock
import dev.ostrom.server.artifacts.fakes.VIEWER
import dev.ostrom.server.artifacts.fakes.alice
import dev.ostrom.server.artifacts.fakes.bob
import dev.ostrom.server.artifacts.fakes.carol
import dev.ostrom.server.artifacts.fakes.dave
import dev.ostrom.server.artifacts.infrastructure.Sha256ContentDigest
import dev.ostrom.server.kernel.signing.Ed25519InstanceSigner
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

class ArtifactUseCasesTest {
    private val repository = InMemoryArtifacts()
    private val clock = TestClock()
    private val digest: ContentDigest = Sha256ContentDigest()
    private val publish = PublishArtifactUseCaseImpl(repository, digest, clock)
    private val read = ReadArtifactsUseCaseImpl(repository)
    private val manage = ManageArtifactUseCaseImpl(repository, clock)

    private fun page(text: String = "<h1>Report</h1>") = listOf(
        UploadedFile("index.html", text.encodeToByteArray()),
        UploadedFile("img/logo.png", byteArrayOf(1, 2, 3)),
    )

    @Test
    fun theDigestIsSha256() = runTest {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            digest.sha256("abc".encodeToByteArray()),
        )
    }

    @Test
    fun publishingStoresEachContentOnceAndVersionsUp() = runTest {
        val created = publish.create(alice, " Weekly report ", "personal", page())
        assertEquals("Weekly report", created.title)
        assertEquals(1, created.latestVersion)
        assertNull(created.linkSlug)
        assertEquals(2, repository.blobs.size)

        clock.now += 1.minutes
        val second = publish.addVersion(alice, created.id, page("<h1>Report v2</h1>"))
        assertEquals(2, second.number)
        assertEquals("index.html", second.entry)
        assertEquals(listOf("img/logo.png", "index.html"), second.files.map { it.path })
        assertEquals(3, repository.blobs.size)
        assertEquals(2, read.get(alice, created.id).latestVersion)
        assertEquals(listOf(2, 1), read.versions(alice, created.id).map { it.number })

        val entry = read.file(alice, created.id, null, null)
        assertEquals("<h1>Report v2</h1>", entry.bytes.decodeToString())
        assertEquals("text/html; charset=utf-8", entry.contentType)
        assertContentEquals(byteArrayOf(1, 2, 3), read.file(alice, created.id, 1, "img/logo.png").bytes)
        assertEquals("<h1>Report</h1>", read.file(alice, created.id, 1, "").bytes.decodeToString())
        assertFailsWith<ArtifactNotFoundException> { read.file(alice, created.id, 3, null) }
        assertFailsWith<ArtifactNotFoundException> { read.file(alice, created.id, 1, "missing.css") }
        repository.blobs.clear()
        assertFailsWith<ArtifactNotFoundException> { read.file(alice, created.id, 1, null) }
    }

    @Test
    fun aLostRaceIsAConflict() = runTest {
        val created = publish.create(alice, "Plan", "team", page())
        repository.loseNextRace = true
        assertFailsWith<ArtifactConflictException> { publish.addVersion(alice, created.id, page("v2")) }
    }

    @Test
    fun publicationIsValidatedFirst() = runTest {
        assertFailsWith<InvalidArtifactRequestException> { publish.create(alice, "x", "public", page()) }
        assertFailsWith<InvalidArtifactRequestException> { publish.create(alice, "", "team", page()) }
        assertFailsWith<InvalidArtifactRequestException> { publish.create(alice, "x", "team", emptyList()) }
        assertTrue(repository.artifacts.isEmpty())
    }

    @Test
    fun readingFollowsAccessSharingAndRoles() = runTest {
        val personal = publish.create(alice, "Mine", "personal", page())
        val team = publish.create(alice, "Ours", "team", page())
        val linked = publish.create(alice, "Public", "link", page())
        assertNotNull(linked.linkSlug)

        assertFailsWith<ArtifactNotFoundException> { read.get(bob, personal.id) }
        assertEquals(team, read.get(bob, team.id))
        assertEquals(linked, read.get(bob, linked.id))
        assertEquals(personal, read.get(dave, personal.id))
        assertFailsWith<ArtifactNotFoundException> { read.get(carol, team.id) }
        assertFailsWith<ArtifactNotFoundException> { read.get(alice, Uuid.random()) }

        manage.share(alice, personal.id, bob.memberId, share = true)
        assertEquals(personal, read.get(bob, personal.id))
        assertEquals(listOf(bob.memberId), manage.members(bob, personal.id))
        manage.share(alice, personal.id, bob.memberId, share = false)
        assertFailsWith<ArtifactNotFoundException> { read.get(bob, personal.id) }
        assertFailsWith<InvalidArtifactRequestException> { manage.share(alice, personal.id, "bob", share = true) }
        // A personal artifact is shared by its owner alone; it stays personal, read by those it is shared with.
        assertFailsWith<ArtifactNotWritableException> { manage.share(dave, personal.id, bob.memberId, share = true) }
        assertFailsWith<ArtifactNotFoundException> { read.get(bob, personal.id) }
        manage.share(alice, personal.id, bob.memberId, share = true)
        assertEquals(ArtifactAccess.PERSONAL, read.get(bob, personal.id).access)
        manage.share(dave, personal.id, bob.memberId, share = false)
        assertFailsWith<ArtifactNotFoundException> { read.get(bob, personal.id) }
        manage.share(dave, team.id, bob.memberId, share = true)
        assertEquals(listOf(bob.memberId), manage.members(bob, team.id))

        assertFailsWith<ArtifactNotWritableException> { publish.addVersion(bob, team.id, page()) }
        assertFailsWith<ArtifactNotWritableException> { publish.addVersion(dave, team.id, page()) }
        assertFailsWith<ArtifactNotWritableException> { manage.update(bob, team.id, "Taken", null) }
        assertFailsWith<ArtifactNotWritableException> { manage.delete(bob, team.id) }
    }

    @Test
    fun listingIsPagedWithinBounds() = runTest {
        repeat(3) { publish.create(alice, "A$it", "team", page()) }
        assertEquals(3, read.list(bob, null, null).size)
        assertEquals(2, read.list(bob, null, 2).size)
        assertFailsWith<InvalidArtifactRequestException> { read.list(bob, null, 0) }
        assertFailsWith<InvalidArtifactRequestException> { read.list(bob, null, 101) }
    }

    @Test
    fun settingsChangeAndTheLinkFollowsTheAccess() = runTest {
        val created = publish.create(alice, "Plan", "personal", page())
        clock.now += 1.minutes
        val linked = manage.update(alice, created.id, null, "link")
        val slug = assertNotNull(linked.linkSlug)
        assertEquals("Plan", linked.title)
        assertEquals(clock.now, linked.updatedAt)
        assertEquals(slug, manage.update(alice, created.id, "Plan B", null).linkSlug)
        assertEquals(slug, manage.update(alice, created.id, null, "link").linkSlug)
        val team = manage.update(dave, created.id, null, "team")
        assertEquals(ArtifactAccess.TEAM, team.access)
        assertNull(team.linkSlug)
        assertNotEquals(slug, manage.update(alice, created.id, null, "link").linkSlug)

        manage.delete(dave, created.id)
        assertFailsWith<ArtifactNotFoundException> { read.get(alice, created.id) }
    }

    @Test
    fun ticketsServeOneArtifactUntilTheyExpire() = runTest {
        val signer = Ed25519InstanceSigner.generate()
        val view = ViewArtifactUseCaseImpl(repository, signer, ArtifactLocations(VIEWER, CONTENT_ORIGIN), clock)
        val created = publish.create(alice, "Plan", "personal", page())
        val other = publish.create(alice, "Other", "personal", page("<p>other</p>"))

        val ticket = view.ticket(alice, created.id, null)
        assertEquals(1, ticket.version)
        assertTrue(ticket.contentUrl.startsWith("$CONTENT_ORIGIN/a/${created.id}/1-"))
        assertTrue(ticket.contentUrl.endsWith("/index.html"))
        assertEquals(0L, ticket.expiresAt.epochSeconds % 600)
        assertTrue(ticket.expiresAt > clock.now + 50.minutes)
        val raw = ticket.contentUrl.removePrefix("$CONTENT_ORIGIN/a/${created.id}/").substringBefore('/')

        assertEquals("<h1>Report</h1>", view.byTicket(created.id, raw, "index.html")?.bytes?.decodeToString())
        assertNull(view.byTicket(other.id, raw, "index.html"))
        assertNull(view.byTicket(created.id, raw.replaceFirst("1-", "2-"), "index.html"))
        val tampered = raw.dropLast(1) + if (raw.last() == '0') '1' else '0'
        assertNull(view.byTicket(created.id, tampered, "index.html"))
        listOf("", "1", "1-2", "x-2-ab", "1-x-ab", "1-2-zz", "1-2-abc", "1-2-ab-cd").forEach {
            assertNull(view.byTicket(created.id, it, "index.html"), it)
        }
        clock.now += 2.hours
        assertNull(view.byTicket(created.id, raw, "index.html"))

        clock.now -= 2.hours
        manage.delete(alice, created.id)
        assertNull(view.byTicket(created.id, raw, "index.html"))
        assertFailsWith<ArtifactNotFoundException> { view.ticket(alice, other.id, 9) }
        assertFailsWith<ArtifactNotFoundException> { view.ticket(bob, other.id, null) }
        assertEquals(1, view.ticket(alice, other.id, 1).version)
    }

    @Test
    fun publicLinksServeTheLatestVersionWhileLinked() = runTest {
        val signer = Ed25519InstanceSigner.generate()
        val view = ViewArtifactUseCaseImpl(repository, signer, ArtifactLocations(VIEWER, CONTENT_ORIGIN), clock)
        val created = publish.create(alice, "Plan", "link", page())
        val slug = assertNotNull(created.linkSlug)
        assertEquals("$CONTENT_ORIGIN/l/$slug/", view.publicUrl(created))
        publish.addVersion(alice, created.id, page("<h1>v2</h1>"))
        assertEquals("<h1>v2</h1>", view.byLink(slug, "")?.bytes?.decodeToString())
        assertNull(view.byLink("unknown", ""))
        repository.artifacts[created.id] = created.copy(access = ArtifactAccess.TEAM)
        assertNull(view.byLink(slug, ""))
        assertNull(view.publicUrl(created.copy(linkSlug = null)))

        val closed = ViewArtifactUseCaseImpl(repository, signer, ArtifactLocations(VIEWER, null), clock)
        assertNull(closed.publicUrl(created))
        assertFailsWith<ArtifactPagesUnavailableException> { closed.ticket(alice, created.id, null) }
    }
}
