// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid
import kotlinx.datetime.TimeZone

/** The longest short name, as GitHub's organization names. */
const val MAX_SLUG = 39

/** The longest organization name. */
const val MAX_ORG_NAME = 100

/** An organization's time zone until an owner or admin sets one. */
const val DEFAULT_TIME_ZONE = "UTC"

/**
 * How long a deleted organization is kept, out of everyone's reach, before it is erased for good (its audit log
 * stays).
 */
val DELETION_GRACE: Duration = 30.days

/** How long a deletion step a module failed on waits before it is tried again; the others go first. */
val DELETION_RETRY: Duration = 10.minutes

/** The zones of the server's time zone database, read once. */
private val KNOWN_ZONES: Set<String> by lazy { TimeZone.availableZoneIds }

/** How old the owner's sign-in may be to delete the organization: the same proof as a yes (S2-54). */
val DELETION_STEP_UP: Duration = 5.minutes

private const val MIN_SLUG = 2
private const val SLUG_ATTEMPTS = 20

/** Hex digits of the random suffix tried once every numbered slug is taken. */
private const val RANDOM_SLUG_SUFFIX = 8
private val SLUG_PATTERN = Regex("[a-z0-9](?:-?[a-z0-9])*")

/** Short names kept for the console's own pages and the API. */
private val RESERVED_SLUGS =
    setOf(
        "admin",
        "api",
        "app",
        "auth",
        "billing",
        "docs",
        "help",
        "login",
        "logout",
        "new",
        "org",
        "orgs",
        "settings",
        "signup",
        "status",
        "support",
        "www",
    )

/**
 * Domains where anyone gets an address: they do not identify a company, so their users hold the
 * free plan as a person, not as a domain.
 */
private val PUBLIC_EMAIL_DOMAINS =
    setOf(
        "gmail.com",
        "googlemail.com",
        "outlook.com",
        "hotmail.com",
        "hotmail.fr",
        "live.com",
        "live.fr",
        "msn.com",
        "yahoo.com",
        "yahoo.fr",
        "icloud.com",
        "me.com",
        "mac.com",
        "proton.me",
        "protonmail.com",
        "pm.me",
        "aol.com",
        "gmx.com",
        "gmx.de",
        "gmx.net",
        "web.de",
        "yandex.ru",
        "yandex.com",
        "mail.ru",
        "free.fr",
        "orange.fr",
        "sfr.fr",
        "laposte.net",
        "wanadoo.fr",
        "hey.com",
        "fastmail.com",
        "zoho.com",
        "tutanota.com",
        "qq.com",
        "163.com",
        "users.noreply.github.com",
    )

/**
 * Checks a short name chosen by a person.
 *
 * @param slug The short name.
 * @return The short name.
 * @throws InvalidOrgRequestException When it is not 2 to 39 lower case letters, digits and single
 *   dashes, or is reserved.
 */
fun validSlug(slug: String): String {
    ensure(slug.length in MIN_SLUG..MAX_SLUG && SLUG_PATTERN.matches(slug)) {
        InvalidOrgRequestException(
            "The short name must be 2 to 39 lower case letters, digits and single dashes."
        )
    }
    ensure(slug !in RESERVED_SLUGS) { InvalidOrgRequestException("This short name is reserved.") }
    return slug
}

/**
 * Checks a time zone: [DEFAULT_TIME_ZONE] or a zone of the time zone database the server knows (`Europe/Paris`), never
 * a raw offset.
 *
 * @param id The zone.
 * @return The zone.
 * @throws InvalidOrgRequestException For any other value.
 */
fun validTimeZone(id: String): String {
    ensure(id == DEFAULT_TIME_ZONE || id in KNOWN_ZONES) {
        InvalidOrgRequestException("The time zone must be a known IANA zone, such as Europe/Paris.")
    }
    return id
}

/**
 * Checks an organization name.
 *
 * @param name The name.
 * @return The trimmed name.
 * @throws InvalidOrgRequestException When it is blank, longer than 100 characters, or has control
 *   characters.
 */
fun validOrgName(name: String): String {
    val trimmed = name.trim()
    ensure(trimmed.length in 1..MAX_ORG_NAME && trimmed.none { it.isISOControl() }) {
        InvalidOrgRequestException(
            "The name must be 1 to 100 characters, without control characters."
        )
    }
    return trimmed
}

/**
 * The short name derived from a name: lower case, every other run of characters a dash.
 *
 * @param name The organization name.
 * @return The base short name, `team` when nothing usable is left.
 */
fun slugOf(name: String): String {
    val cleaned =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(MAX_SLUG).trimEnd('-')
    return cleaned.takeIf { it.length >= MIN_SLUG && it !in RESERVED_SLUGS } ?: "team"
}

/**
 * A free short name derived from [base]: [base], then `base-2` to `base-20`, then `base-` and 8
 * random hex digits.
 *
 * @param base The base short name.
 * @param taken Says whether a short name is used.
 * @return The short name.
 */
suspend fun freeSlug(base: String, taken: suspend (String) -> Boolean): String {
    if (!taken(base)) return base
    val numbered =
        (2..SLUG_ATTEMPTS).asSequence().map { suffixed(base, "-$it") }.firstOrNull { !taken(it) }
    return numbered ?: suffixed(base, "-" + Uuid.random().toHexString().take(RANDOM_SLUG_SUFFIX))
}

private fun suffixed(base: String, suffix: String) =
    base.take(MAX_SLUG - suffix.length).trimEnd('-') + suffix

/**
 * What a new organization's free plan is held against: the creator's company domain when their
 * e-mail is verified and not on a public mail domain, otherwise the creator.
 *
 * @param userId The creator.
 * @param verifiedEmails The creator's verified addresses.
 * @return `domain:<domain>` or `user:<id>`.
 */
fun freeClaimOf(userId: Uuid, verifiedEmails: Set<String>): String {
    val domain =
        verifiedEmails
            .sorted()
            .map { it.substringAfterLast('@').lowercase() }
            .firstOrNull { it.isNotEmpty() && it !in PUBLIC_EMAIL_DOMAINS }
    return if (domain != null) "domain:$domain" else "user:$userId"
}

/**
 * How a free claim reads in a message to the person who hit it.
 *
 * @param claim The claim.
 * @return `the domain acme.com`, `the GitHub organization 42` or `your account`.
 */
fun describeClaim(claim: String): String =
    when (claim.substringBefore(':')) {
        "domain" -> "the domain ${claim.substringAfter(':')}"
        "github" -> "this GitHub organization"
        else -> "your account"
    }
