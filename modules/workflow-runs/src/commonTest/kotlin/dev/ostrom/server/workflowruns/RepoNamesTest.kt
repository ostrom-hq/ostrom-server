// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns

import dev.ostrom.server.workflowruns.domain.usecases.RepoNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepoNamesTest {
    @Test
    fun everyFormOfARepositorysNameHasTheSameKey() {
        val forms = listOf(
            "acme/api",
            " Acme/API ",
            "https://github.com/acme/api.git",
            "https://github.com/acme/api/",
            "git@github.com:acme/api",
            "ssh://git@github.com/acme/api.git",
            "github.com//acme/api",
        )
        assertEquals(setOf("acme/api"), forms.map(RepoNames::key).toSet())
        assertEquals("api", RepoNames.key("api"), "The CLI's folder name stays bare")
    }

    @Test
    fun aBareNameIsTheNamePartOfAFullOneButNeverAnotherRepository() {
        assertTrue(RepoNames.same("https://github.com/acme/api", "acme/api"))
        assertTrue(RepoNames.same("api", "acme/api"))
        assertTrue(RepoNames.same("acme/api", "api"))
        assertFalse(RepoNames.same("acme/api", "acme/docs"))
        assertFalse(RepoNames.same("acme/api", "other/api"))
        assertFalse(RepoNames.same("api", "acme/docs"))
        assertFalse(RepoNames.same("api", "docs"))
    }
}
