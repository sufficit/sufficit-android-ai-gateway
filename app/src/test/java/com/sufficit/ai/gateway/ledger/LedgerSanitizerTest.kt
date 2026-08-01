package com.sufficit.ai.gateway.ledger

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerSanitizerTest {
    @Test
    fun sensitiveKeysAndBearerValuesAreRedactedBeforePersistence() {
        val sanitized = LedgerSanitizer.sanitizeJson(
            JSONObject()
                .put("accessToken", "super-secret")
                .put("nested", JSONObject().put("password", "123").put("safe", "ok"))
                .put("message", "Authorization: Bearer abc.def.ghi")
        )

        assertEquals("[REDACTED]", sanitized.getString("accessToken"))
        assertEquals("[REDACTED]", sanitized.getJSONObject("nested").getString("password"))
        assertEquals("ok", sanitized.getJSONObject("nested").getString("safe"))
        assertFalse(sanitized.getString("message").contains("abc.def.ghi"))
    }

    @Test
    fun canonicalArgumentHashDoesNotDependOnObjectKeyOrder() {
        val first = JSONObject().put("b", 2).put("a", 1)
        val second = JSONObject().put("a", 1).put("b", 2)

        assertEquals(
            LedgerSanitizer.sha256(LedgerSanitizer.canonicalJson(first)),
            LedgerSanitizer.sha256(LedgerSanitizer.canonicalJson(second))
        )
        assertNotEquals(LedgerSanitizer.sha256("one"), LedgerSanitizer.sha256("two"))
        assertTrue(LedgerSanitizer.sha256("one").length == 64)
    }
}
