// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.testing

import io.github.smyrgeorge.sqlx4k.Driver
import io.ktor.client.HttpClient
import org.koin.core.Koin

/**
 * What a server test can use.
 *
 * @property koin The test server's Koin, to reach a repository or use case directly.
 * @property driver The test database, connected as the serving role, like the server: row-level security applies.
 * @property client An HTTP client to the test server that reads and writes JSON.
 * @property database The test database with its owner and superuser pools, for what no request does (see
 *   [TestDatabase.superuser]).
 */
class TestServer(
    val koin: Koin,
    val driver: Driver,
    val client: HttpClient,
    val database: TestDatabase,
)
