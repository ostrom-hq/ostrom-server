// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import java.sql.Timestamp
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.statements.api.RowApi
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/**
 * A `TIMESTAMPTZ` column read and written as a [kotlin.time.Instant], the type of every time the modules store.
 *
 * The value crosses the driver as an [OffsetDateTime] in UTC, never as a `java.sql.Timestamp`, whose conversion
 * reads the JVM's default time zone. PostgreSQL keeps microseconds: a nanosecond instant reads back rounded to the
 * nearest one.
 */
class InstantColumnType : ColumnType<Instant>() {
    override fun sqlType(): String = "TIMESTAMP WITH TIME ZONE"

    override fun valueFromDB(value: Any): Instant = when (value) {
        is Instant -> value
        is OffsetDateTime -> value.toInstant().toKotlinInstant()
        is java.time.Instant -> value.toKotlinInstant()
        // What pgjdbc gives for a TIMESTAMPTZ read without a target type (an expression's): an instant, zone-free.
        is Timestamp -> value.toInstant().toKotlinInstant()
        else -> error("Unexpected value for a TIMESTAMPTZ column: ${value::class.simpleName}")
    }

    override fun notNullValueToDB(value: Instant): Any = OffsetDateTime.ofInstant(value.toJavaInstant(), ZoneOffset.UTC)

    override fun nonNullValueToString(value: Instant): String = "'${notNullValueToDB(value)}'"

    override fun readObject(rs: RowApi, index: Int): Any? = rs.getObject(index, OffsetDateTime::class.java)
}

/**
 * A `TIMESTAMPTZ` column of [kotlin.time.Instant] ([InstantColumnType]).
 *
 * @param name The column's name.
 *
 * @return The column.
 */
fun Table.instant(name: String): Column<Instant> = registerColumn(name, InstantColumnType())
