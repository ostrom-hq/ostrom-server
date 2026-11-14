// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.ArtifactAccess
import run.ostrom.server.artifacts.domain.ArtifactConflictException
import run.ostrom.server.artifacts.domain.ArtifactEvents
import run.ostrom.server.artifacts.domain.ArtifactLocations
import run.ostrom.server.artifacts.domain.ArtifactNotFoundException
import run.ostrom.server.artifacts.domain.ArtifactNotWritableException
import run.ostrom.server.artifacts.domain.ArtifactPagesUnavailableException
import run.ostrom.server.artifacts.domain.ArtifactPersonRequiredException
import run.ostrom.server.artifacts.domain.ArtifactSecretException
import run.ostrom.server.artifacts.domain.ArtifactTicket
import run.ostrom.server.artifacts.domain.ArtifactTicketsRateLimitedException
import run.ostrom.server.artifacts.domain.ContentDigest
import run.ostrom.server.artifacts.domain.InvalidArtifactRequestException
import run.ostrom.server.artifacts.domain.ManageArtifactUseCaseImpl
import run.ostrom.server.artifacts.domain.PublishArtifactUseCaseImpl
import run.ostrom.server.artifacts.domain.ReadArtifactsUseCaseImpl
import run.ostrom.server.artifacts.domain.UploadedFile
import run.ostrom.server.artifacts.domain.ViewArtifactUseCaseImpl
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageUnavailableException
import run.ostrom.server.artifacts.domain.storage.BucketArtifactContents
import run.ostrom.server.artifacts.fakes.CONTENT_ORIGIN
import run.ostrom.server.artifacts.fakes.InMemoryArtifacts
import run.ostrom.server.artifacts.fakes.InMemoryBucket
import run.ostrom.server.artifacts.fakes.InMemoryStorage
import run.ostrom.server.artifacts.fakes.ORG_A
import run.ostrom.server.artifacts.fakes.ORG_B
import run.ostrom.server.artifacts.fakes.TestClock
import run.ostrom.server.artifacts.fakes.TestViewers
import run.ostrom.server.artifacts.fakes.VIEWER
import run.ostrom.server.artifacts.fakes.active
import run.ostrom.server.artifacts.fakes.alice
import run.ostrom.server.artifacts.fakes.aliceAgent
import run.ostrom.server.artifacts.fakes.bob
import run.ostrom.server.artifacts.fakes.carol
import run.ostrom.server.artifacts.fakes.dave
import run.ostrom.server.artifacts.fakes.gateOf
import run.ostrom.server.artifacts.infrastructure.SealedViewTickets
import run.ostrom.server.artifacts.infrastructure.Sha256ContentDigest
import run.ostrom.server.kernel.errors.InvalidPageException
import run.ostrom.server.kernel.signing.Ed25519InstanceSigner
import run.ostrom.server.kernel.signing.InstanceSigner
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class ArtifactUseCasesTest {
    private val repository = InMemoryArtifacts()
    private val clock = TestClock()
    private val digest: ContentDigest = Sha256ContentDigest()
    private val events = mutableListOf<String>()
    private val recorder = ArtifactEvents { caller, event, id, details ->
        events += "${caller.memberId} $event ${id != null} ${details.entries.joinToString(",")}"
    }
    private val storage = InMemoryStorage().active(ORG_A, ORG_B)
    private val bucket = InMemoryBucket()
    private val contents = BucketArtifactContents(storage, bucket)
    private val publish =
        PublishArtifactUseCaseImpl(repository, contents, digest, clock, recorder, gateOf(storage, clock))
    private val read = ReadArtifactsUseCaseImpl(repository, contents)
    private val manage = ManageArtifactUseCaseImpl(repository, contents, clock, recorder)
    private var signer: InstanceSigner? = null

    private suspend fun signer(): InstanceSigner = signer ?: Ed25519InstanceSigner.generate().also { signer = it }

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
    fun theAuditLogIsToldOfEachChangeAndEachRefusalNeverOfAReading() = runTest {
        val leaked = listOf(UploadedFile("notes.md", ("token: ghp_" + "a".repeat(36)).encodeToByteArray()))
        assertFailsWith<ArtifactPersonRequiredException> { publish.create(aliceAgent, "Leak", "link", page()) }
        assertFailsWith<ArtifactSecretException> { publish.create(alice, "Leak", "team", leaked) }
        val artifact = publish.create(alice, "Report", "personal", page())
        assertFailsWith<ArtifactSecretException> { publish.addVersion(alice, artifact.id, leaked) }
        publish.addVersion(alice, artifact.id, page("<h1>Two</h1>"))
        manage.update(alice, artifact.id, "Renamed", null)
        manage.update(alice, artifact.id, null, "personal")
        manage.update(alice, artifact.id, null, "link")
        manage.share(alice, artifact.id, bob.memberId, share = true)
        read.get(bob, artifact.id)
        read.list(bob, null, null)
        manage.share(alice, artifact.id, bob.memberId, share = false)
        manage.delete(alice, artifact.id)
        val a = alice.memberId
        assertEquals(
            listOf(
                "$a publish_refused false access=link,reason=person_required",
                "$a publish_refused false reason=secret",
                "$a created true title=Report,access=personal",
                "$a publish_refused true reason=secret",
                "$a version_published true version=2",
                "$a access_changed true from=personal,to=link",
                "$a member_added true member=${bob.memberId}",
                "$a member_removed true member=${bob.memberId}",
                "$a deleted true ",
            ),
            events,
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
        // Three objects in the bucket, none in PostgreSQL.
        assertEquals(3, bucket.objects.size)
        bucket.objects.clear()
        assertFailsWith<ArtifactStorageUnavailableException> { read.file(alice, created.id, 1, null) }
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
        assertFailsWith<ArtifactNotFoundException> { read.get(dave, personal.id) }
        assertFailsWith<ArtifactNotFoundException> { read.get(carol, team.id) }
        assertFailsWith<ArtifactNotFoundException> { read.get(alice, Uuid.random()) }

        manage.share(alice, personal.id, bob.memberId, share = true)
        assertEquals(personal, read.get(bob, personal.id))
        assertEquals(listOf(bob.memberId), manage.members(bob, personal.id))
        manage.share(alice, personal.id, bob.memberId, share = false)
        assertFailsWith<ArtifactNotFoundException> { read.get(bob, personal.id) }
        assertFailsWith<InvalidArtifactRequestException> { manage.share(alice, personal.id, "bob", share = true) }

        assertFailsWith<ArtifactNotWritableException> { publish.addVersion(bob, team.id, page()) }
        assertFailsWith<ArtifactNotWritableException> { publish.addVersion(dave, team.id, page()) }
        assertFailsWith<ArtifactNotWritableException> { manage.update(bob, team.id, "Taken", null) }
        assertFailsWith<ArtifactNotWritableException> { manage.delete(bob, team.id) }
    }

    @Test
    fun anAdministratorHasNoRightOverAnotherMembersArtifact() = runTest {
        val personal = publish.create(alice, "Mine", "personal", page())
        val team = publish.create(alice, "Ours", "team", page())
        assertFailsWith<ArtifactNotFoundException> { read.get(dave, personal.id) }
        assertFailsWith<ArtifactNotFoundException> { read.versions(dave, personal.id) }
        assertFailsWith<ArtifactNotFoundException> { read.file(dave, personal.id, null, null) }
        assertFailsWith<ArtifactNotFoundException> { manage.members(dave, personal.id) }
        assertFailsWith<ArtifactNotFoundException> { manage.update(dave, personal.id, null, "link") }
        assertFailsWith<ArtifactNotFoundException> { manage.share(dave, personal.id, dave.memberId, share = true) }
        assertFailsWith<ArtifactNotFoundException> { manage.delete(dave, personal.id) }
        assertFailsWith<ArtifactNotWritableException> { manage.update(dave, team.id, null, "link") }
        assertFailsWith<ArtifactNotWritableException> { manage.update(dave, team.id, null, "personal") }
        assertFailsWith<ArtifactNotWritableException> { manage.share(dave, team.id, dave.memberId, share = true) }
        assertFailsWith<ArtifactNotWritableException> { manage.delete(dave, team.id) }
        assertEquals(ArtifactAccess.TEAM, read.get(alice, team.id).access)

        manage.share(alice, personal.id, dave.memberId, share = true)
        assertEquals(personal, read.get(dave, personal.id), "Shared with them, they read it as any member")
        assertFailsWith<ArtifactNotWritableException> { manage.delete(dave, personal.id) }
    }

    @Test
    fun onlyAPersonMakesAnArtifactPublicChangesItsAccessOrDeletesIt() = runTest {
        assertFailsWith<ArtifactPersonRequiredException> { publish.create(aliceAgent, "Leak", "link", page()) }
        assertTrue(repository.artifacts.isEmpty())
        val team = publish.create(aliceAgent, "Ours", "team", page())
        val personal = publish.create(aliceAgent, "Mine", "personal", page())
        assertFailsWith<ArtifactPersonRequiredException> { manage.update(aliceAgent, personal.id, null, "link") }
        assertFailsWith<ArtifactPersonRequiredException> { manage.update(aliceAgent, team.id, null, "personal") }
        assertFailsWith<ArtifactPersonRequiredException> { manage.delete(aliceAgent, personal.id) }
        assertEquals("Renamed", manage.update(aliceAgent, personal.id, "Renamed", null).title)
        assertEquals(ArtifactAccess.TEAM, manage.update(aliceAgent, team.id, null, "team").access, "Unchanged")
        manage.share(aliceAgent, personal.id, bob.memberId, share = true)
        assertEquals(2, publish.addVersion(aliceAgent, personal.id, page("v2")).number)

        assertNotNull(manage.update(alice, personal.id, null, "link").linkSlug)
        manage.delete(alice, team.id)
        assertFailsWith<ArtifactNotFoundException> { read.get(alice, team.id) }
    }

    @Test
    fun listingIsPagedWithinBounds() = runTest {
        repeat(3) { publish.create(alice, "A$it", "team", page()) }
        assertEquals(3, read.list(bob, null, null).size)
        assertEquals(2, read.list(bob, null, 2).size)
        assertFailsWith<InvalidArtifactRequestException> { read.list(bob, null, 0) }
        assertFailsWith<InvalidArtifactRequestException> { read.list(bob, null, 101) }
        val last = read.list(bob, null, null).last()
        assertEquals(emptyList(), read.list(bob, last.id, null), "After the last")
        assertFailsWith<InvalidPageException> { read.list(bob, Uuid.random(), null) }
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
        val team = manage.update(alice, created.id, null, "team")
        assertEquals(ArtifactAccess.TEAM, team.access)
        assertNull(team.linkSlug)
        assertNotEquals(slug, manage.update(alice, created.id, null, "link").linkSlug)

        manage.delete(alice, created.id)
        assertFailsWith<ArtifactNotFoundException> { read.get(alice, created.id) }
    }

    private val viewers = TestViewers()

    private suspend fun viewing(maxCached: Int = 10_000, origin: String? = CONTENT_ORIGIN) = ViewArtifactUseCaseImpl(
        repository,
        contents,
        SealedViewTickets(signer()),
        viewers,
        ArtifactLocations(VIEWER, origin),
        clock,
        maxCached = maxCached,
    )

    private fun ArtifactTicket.sealed(): String =
        contentUrl.removePrefix("$CONTENT_ORIGIN/a/$artifactId/").substringBefore('/')

    @Test
    fun ticketsAreSealedForTheirReaderAndLastEightHours() = runTest {
        val view = viewing()
        val created = publish.create(alice, "Plan", "personal", page())
        val other = publish.create(alice, "Other", "personal", page("<p>other</p>"))

        val ticket = view.ticket(alice, created.id, null)
        assertEquals(1, ticket.version)
        assertTrue(ticket.contentUrl.endsWith("/index.html"))
        assertEquals(8.hours to 10.minutes, ViewArtifactUseCaseImpl.LIFETIME to ViewArtifactUseCaseImpl.ROUNDING)
        assertEquals(0L, ticket.expiresAt.epochSeconds % 600)
        assertTrue(ticket.expiresAt > clock.now + 7.hours + 50.minutes)
        assertTrue(ticket.expiresAt <= clock.now + 8.hours)
        val raw = ticket.sealed()
        assertFalse(alice.memberId in raw || "alice-session" in raw, "The page learns nothing of its reader")
        assertEquals(raw, view.ticket(alice, created.id, 1).sealed(), "Reloading reuses the address")

        assertEquals("<h1>Report</h1>", view.byTicket(created.id, raw, "index.html")?.bytes?.decodeToString())
        assertNull(view.byTicket(other.id, raw, "index.html"), "Another artifact's")
        assertNull(view.byTicket(created.id, raw.drop(1), "index.html"), "Forged")
        clock.now += 8.hours
        assertNull(view.byTicket(created.id, raw, "index.html"), "Expired")
        clock.now -= 8.hours

        manage.delete(alice, created.id)
        assertNull(view.byTicket(created.id, raw, "index.html"), "A deleted artifact stops at once")
        assertFailsWith<ArtifactNotFoundException> { view.ticket(alice, other.id, 9) }
        assertFailsWith<ArtifactNotFoundException> { view.ticket(bob, other.id, null) }
        assertFailsWith<ArtifactNotFoundException> { view.ticket(dave, other.id, null) }
        assertEquals(1, view.ticket(alice, other.id, 1).version)
    }

    @Test
    fun onlyAPersonGetsATicket() = runTest {
        val view = viewing()
        val created = publish.create(alice, "Plan", "team", page())
        listOf(aliceAgent, alice.copy(credentialId = null), alice.copy(subjectId = null)).forEach {
            assertFailsWith<ArtifactPersonRequiredException> { view.ticket(it, created.id, null) }
        }
        assertFailsWith<ArtifactPagesUnavailableException> { viewing(origin = null).ticket(alice, created.id, null) }
    }

    @Test
    fun aTicketStopsWithinItsRecheckWhenItsReaderLosesTheArtifact() = runTest {
        val view = viewing()
        val created = publish.create(alice, "Plan", "personal", page())
        manage.share(alice, created.id, bob.memberId, share = true)
        val forBob = view.ticket(bob, created.id, null).sealed()
        val forAlice = view.ticket(alice, created.id, null).sealed()
        assertNotNull(view.byTicket(created.id, forBob, ""))
        assertEquals(1, viewers.checks)
        assertNotNull(view.byTicket(created.id, forBob, ""))
        assertEquals(1, viewers.checks, "Checked again only past the recheck")

        manage.share(alice, created.id, bob.memberId, share = false)
        assertNotNull(view.byTicket(created.id, forBob, ""), "Within the 30 seconds")
        clock.now += 31.seconds
        assertNull(view.byTicket(created.id, forBob, ""), "No longer shared with them")
        manage.share(alice, created.id, bob.memberId, share = true)
        assertNotNull(view.byTicket(created.id, forBob, ""))

        clock.now += 31.seconds
        viewers.signedOut += "bob-session"
        assertNull(view.byTicket(created.id, forBob, ""), "Signed out")
        assertNotNull(view.byTicket(created.id, forAlice, ""), "Another reader's ticket keeps working")
        clock.now += 31.seconds
        viewers.removed += alice.memberId
        assertNull(view.byTicket(created.id, forAlice, ""), "Left the organization")
    }

    @Test
    fun aCredentialGetsSixtyTicketsAMinute() = runTest {
        val view = viewing(maxCached = 2)
        val created = publish.create(alice, "Plan", "team", page())
        repeat(ViewArtifactUseCaseImpl.TICKETS_PER_MINUTE) { view.ticket(alice, created.id, null) }
        clock.now += 20.seconds
        val limited = assertFailsWith<ArtifactTicketsRateLimitedException> { view.ticket(alice, created.id, null) }
        assertEquals(41, limited.retryAfterSeconds)
        view.ticket(bob, created.id, null)
        clock.now += 41.seconds
        view.ticket(alice, created.id, null)

        // Past its bounds, a replica forgets the oldest windows and grants first.
        view.ticket(dave, created.id, null)
        repeat(ViewArtifactUseCaseImpl.TICKETS_PER_MINUTE) { view.ticket(bob, created.id, null) }
        val tickets = listOf(alice, bob, dave).map {
            view.ticket(it.copy(credentialId = "${it.subjectId}-app"), created.id, null)
        }
        tickets.forEach { assertNotNull(view.byTicket(created.id, it.sealed(), "")) }
        assertEquals(3, viewers.checks)
        tickets.forEach { assertNotNull(view.byTicket(created.id, it.sealed(), "")) }
        assertEquals(6, viewers.checks, "Two cached at most: each read checked again")
    }

    @Test
    fun publicLinksServeTheLatestVersionWhileLinked() = runTest {
        val view = viewing()
        val created = publish.create(alice, "Plan", "link", page())
        val slug = assertNotNull(created.linkSlug)
        assertEquals("$CONTENT_ORIGIN/l/$slug/", view.publicUrl(created))
        publish.addVersion(alice, created.id, page("<h1>v2</h1>"))
        assertEquals("<h1>v2</h1>", view.byLink(slug, "")?.bytes?.decodeToString())
        assertNull(view.byLink("unknown", ""))
        repository.artifacts[created.id] = created.copy(access = ArtifactAccess.TEAM)
        assertNull(view.byLink(slug, ""))
        assertNull(view.publicUrl(created.copy(linkSlug = null)))

        assertNull(viewing(origin = null).publicUrl(created))
    }
}
