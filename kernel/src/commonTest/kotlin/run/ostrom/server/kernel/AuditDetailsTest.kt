// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import run.ostrom.server.kernel.audit.MAX_AUDIT_DETAIL
import run.ostrom.server.kernel.audit.auditDetails
import kotlin.test.Test
import kotlin.test.assertEquals

class AuditDetailsTest {
    @Test
    fun anAbsentFactIsLeftOutAndAListIsJoined() {
        val details = auditDetails("member" to null, "usd_per_month" to 12.5, "tools" to listOf("send", "read"))

        assertEquals(mapOf("usd_per_month" to "12.5", "tools" to "send,read"), details)
    }

    @Test
    fun aLongValueIsCut() {
        val long = "x".repeat(MAX_AUDIT_DETAIL + 50)

        assertEquals(MAX_AUDIT_DETAIL, auditDetails("tools" to setOf(long))["tools"]?.length)
        assertEquals(MAX_AUDIT_DETAIL, auditDetails("prefix" to long)["prefix"]?.length)
    }
}
