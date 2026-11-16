// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.testing

import io.ktor.client.HttpClient
import org.koin.core.Koin

/**
 * What a server test can use.
 *
 * @property koin The test server's Koin, to reach a repository or use case directly (the serving role's
 *   `JdbcDatabase`, row-level security applying, as the server's).
 * @property recorder Records the statements of a request on demand ([StatementRecorder.record]).
 * @property client An HTTP client to the test server that reads and writes JSON.
 * @property database The test database with its owner and superuser pools, for what no request does (see
 *   [TestDatabase.superuserJdbc]).
 */
class TestServer(
    val koin: Koin,
    val recorder: StatementRecorder,
    val client: HttpClient,
    val database: TestDatabase,
)
