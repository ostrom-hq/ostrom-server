// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts.domain.storage

import run.ostrom.server.kernel.network.parseIpAddress

/** The rules of the bucket's fields and of the settings, TeamCode's (`ArtifactAdministrationService.validate`). */
object StorageRules {
    private const val MAX_ENDPOINT = 512
    private const val MAX_PREFIX = 200
    private const val MAX_SEGMENTS = 8
    private const val MIN_SECRET = 8
    private const val MAX_SECRET = 512
    private const val MAX_ARTIFACTS = 100_000
    private const val MAX_PUBLISHES = 10_000
    private const val MAX_DAYS = 3650
    private const val MAX_VERSIONS = 1000
    private const val MAX_BYTES = 100L * 1024 * ArtifactQuotas.GIB
    private const val IPV4 = 4
    private const val LINK_LOCAL_FIRST = 169
    private const val LINK_LOCAL_SECOND = 254
    private const val V6_LINK_LOCAL = 0xfe
    private const val V6_LINK_LOCAL_MASK = 0xc0
    private const val V6_LINK_LOCAL_BITS = 0x80
    private const val BYTE_MASK = 0xff
    private val REGION = Regex("[a-z0-9-]{1,64}")
    private val BUCKET = Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
    private val PREFIX_SEGMENT = Regex("[a-z0-9._-]{1,64}")
    private val ACCESS_KEY = Regex("[A-Za-z0-9+/=._-]{3,256}")
    private val NUMERIC_LABEL = Regex("0x[0-9a-f]*|[0-9]+")
    private val METADATA_NAMES = setOf("metadata", "metadata.google.internal", "metadata.goog", "instance-data")

    /** `fd00:ec2::/32`, the EC2 metadata service's IPv6 address range. */
    private val EC2_METADATA_V6 = listOf(0xfd, 0x00, 0x0e, 0xc2)

    /**
     * Checks what an owner or an administrator saves.
     *
     * @throws InvalidArtifactStorageException When a field is malformed.
     * @throws ArtifactStorageHostNotAllowedException When the endpoint's host is link-local, unspecified or metadata.
     */
    fun validate(input: ArtifactStorageInput) {
        val malformed = fieldErrors(input).firstOrNull { (wrong, _) -> wrong }
        if (malformed != null) throw InvalidArtifactStorageException(malformed.second)
        if (!allowedHost(input.endpoint)) throw ArtifactStorageHostNotAllowedException()
    }

    /** Each rule of a field, with what the person reads when it fails, in the order they are checked. */
    private fun fieldErrors(input: ArtifactStorageInput): List<Pair<Boolean, String>> {
        val secret = input.secretAccessKey
        return listOf(
            !validEndpoint(input.endpoint) to "Use an https endpoint without a path, credentials, query or " +
                "fragment, such as https://s3.gra.io.cloud.ovh.net.",
            !REGION.matches(input.region) to "Use a region of lowercase letters, digits and dashes, such as auto.",
            (!BUCKET.matches(input.bucket) || ".." in input.bucket) to
                "Use a bucket of 3 to 63 lowercase letters, digits, dots and dashes.",
            !validPrefix(input.prefix) to "Use a prefix of up to 8 segments of lowercase letters, digits, dots, " +
                "underscores and dashes separated by slashes, without . or .. segments, or leave it empty.",
            !ACCESS_KEY.matches(input.accessKeyId) to "Paste the access key id without spaces.",
            (secret != null && !validSecret(secret)) to "Paste the secret access key without spaces.",
        )
    }

    private fun validSecret(secret: String) = secret.length in MIN_SECRET..MAX_SECRET && secret.none(::unprintable)

    private fun unprintable(char: Char) = char.isWhitespace() || char.isISOControl()

    /**
     * Whether an endpoint is `https://host[:port]`, with an optional trailing slash and nothing else.
     *
     * @return True when it is.
     */
    fun validEndpoint(endpoint: String): Boolean {
        val authority = endpoint.removePrefix("https://").removeSuffix("/")
        val shaped = endpoint.startsWith("https://") && endpoint.length <= MAX_ENDPOINT
        return shaped && endpoint.none(::unprintable) && authority.isNotEmpty() && authority.none { it in "/?#@\\" }
    }

    /**
     * Whether an endpoint's host may be reached: never a cloud metadata name, nor a link-local, unspecified or EC2
     * metadata address; loopback and private addresses pass (a MinIO next to the server). A host that looks numeric
     * in any other spelling than the canonical one (`2852039166`, `0xa9fea9fe`, `0251.0376.0251.0376`) is refused
     * rather than decoded.
     *
     * @return True when it may.
     */
    fun allowedHost(endpoint: String): Boolean {
        val host = hostOf(endpoint)
        val numeric = ':' in host || host.split('.').all { NUMERIC_LABEL.matches(it) }
        return when {
            host.isEmpty() || host in METADATA_NAMES -> false
            !numeric -> true
            else -> {
                val address = parseIpAddress(host)
                address != null && allowedAddress(address.map { it.toInt() and BYTE_MASK })
            }
        }
    }

    private fun hostOf(endpoint: String): String {
        val authority = endpoint.removePrefix("https://").substringBefore('/')
        val host = if (authority.startsWith("[")) {
            authority.substringAfter('[').substringBefore(']')
        } else {
            authority.substringBefore(':')
        }
        return host.lowercase().trimEnd('.')
    }

    private fun allowedAddress(bytes: List<Int>): Boolean = when {
        bytes.all { it == 0 } -> false
        bytes.size == IPV4 -> bytes[0] != 0 && !(bytes[0] == LINK_LOCAL_FIRST && bytes[1] == LINK_LOCAL_SECOND)
        bytes[0] == V6_LINK_LOCAL && (bytes[1] and V6_LINK_LOCAL_MASK) == V6_LINK_LOCAL_BITS -> false
        else -> bytes.take(EC2_METADATA_V6.size) != EC2_METADATA_V6
    }

    /**
     * Whether a prefix is empty or up to 8 `[a-z0-9._-]` segments, without `.` or `..`.
     *
     * @return True when it is.
     */
    fun validPrefix(prefix: String): Boolean {
        val segments = prefix.split('/')
        return prefix.isEmpty() || prefix.length <= MAX_PREFIX && segments.size <= MAX_SEGMENTS &&
            segments.all { PREFIX_SEGMENT.matches(it) && it != "." && it != ".." }
    }

    /**
     * Checks the settings' ranges.
     *
     * @throws InvalidArtifactSettingsException When one is out of its range.
     */
    fun validate(settings: ArtifactSettings) {
        val quotas = settings.quotas
        val bytes = ArtifactQuotas.MIB..MAX_BYTES
        val quotasValid = listOf(
            quotas.memberBytes in bytes,
            quotas.orgBytes in bytes,
            quotas.artifactsPerMember in 1..MAX_ARTIFACTS,
            quotas.publishesPerHour in 1..MAX_PUBLISHES,
        )
        if (false in quotasValid) {
            throw InvalidArtifactSettingsException(
                "Use quotas of 1 MiB to 100 TiB per member and per organization, 1 to 100000 artifacts per member " +
                    "and 1 to 10000 publications per hour.",
            )
        }
        val retention = settings.retention
        val days = 1..MAX_DAYS
        val retentionValid = listOf(
            retention.personalDays.inOrNull(days),
            retention.sharedDays.inOrNull(days),
            retention.maxVersionsPerArtifact in 1..MAX_VERSIONS,
        )
        if (false in retentionValid) {
            throw InvalidArtifactSettingsException(
                "Use retentions of 1 to 3650 days (or none) and 1 to 1000 versions per artifact.",
            )
        }
    }
}

/** Whether a value is in [range], or absent. */
private fun Int?.inOrNull(range: IntRange): Boolean = this == null || this in range
