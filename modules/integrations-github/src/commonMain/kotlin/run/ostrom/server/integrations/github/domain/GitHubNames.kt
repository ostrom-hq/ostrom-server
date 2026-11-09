// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.integrations.github.domain

/**
 * Whether a text is a repository's full name as GitHub writes it (`owner/name`): the only repository text put in a
 * path of GitHub's API (AUDIT-SECU-2 B5). An owner is a GitHub login (letters, digits and hyphens, 39 at most); a name
 * is letters, digits, `.`, `_` and `-`, 100 at most, and never `.` or `..`: `x/../../orgs/acme/members` never makes
 * the installation's token call another route.
 *
 * @param repo The text.
 * @return True for a repository's full name.
 */
fun isRepositoryName(repo: String): Boolean = REPOSITORY.matches(repo)

// A name is 1 to 100 characters (the lookahead); it may start with a dot (`.github`) but is never only dots.
private val REPOSITORY =
    Regex("^[A-Za-z0-9][A-Za-z0-9-]{0,38}/(?=[A-Za-z0-9._-]{1,100}$)\\.?[A-Za-z0-9_-][A-Za-z0-9._-]*$")
