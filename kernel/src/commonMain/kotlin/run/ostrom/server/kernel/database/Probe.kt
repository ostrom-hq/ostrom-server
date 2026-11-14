// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

// Probe (not to merge): can a jvm-only commonMain use java.* and a jvmMain dependency (Exposed), and a jvmMain class?
internal fun probeJvm(): String =
    java.util.UUID.randomUUID().toString() + org.jetbrains.exposed.v1.jdbc.Database::class.simpleName +
        JdbcDatabase::class.simpleName
