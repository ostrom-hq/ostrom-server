// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.domain.usecases

/**
 * The name a repository goes by, whatever form names it: a cloud session's URL (`https://github.com/Acme/API.git`), a
 * remote (`git@github.com:acme/api`), the CLI's `owner/name`, or the folder's name when the CLI found no remote.
 */
internal object RepoNames {
    /**
     * Tells whether two names are the same repository: the same `owner/name`, or a bare name and the name part of the
     * other.
     *
     * @param a A name.
     * @param b Another.
     * @return True when they name the same repository.
     */
    fun same(a: String, b: String): Boolean {
        val left = key(a)
        val right = key(b)
        return left == right || bare(left, right) || bare(right, left)
    }

    /**
     * The `owner/name` of a name, lower case, without its host, its scheme or `.git`; a bare name stays bare.
     *
     * @param repo The name.
     * @return Its key.
     */
    fun key(repo: String): String {
        val path = repo.trim().lowercase().substringAfter("://").substringAfter('@').replace(':', '/')
        val parts = path.removeSuffix("/").removeSuffix(".git").split('/').filter { it.isNotEmpty() }
        return parts.takeLast(2).joinToString("/")
    }

    private fun bare(name: String, full: String): Boolean = '/' !in name && full.substringAfterLast('/') == name
}
