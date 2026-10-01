package com.aaravsekhri.dot

import com.aaravsekhri.dot.account.Allowance
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What counts as a confirmed purchase, read from the service's own answer. */
class PurchaseConfirmationTest {

    private fun allowance(plus: String, extra: String = "") = Allowance.parse(
        JSONObject("""{"can_start": true, "welcome_left": 0, "plus": $plus, "free_courses": ["maps"]$extra}"""),
    )

    @Test
    fun `a verified weekly purchase confirms`() {
        val a = allowance("""{"active": true, "status": "verified", "promotional": false, "product": "dot_plus_weekly"}""")
        assertTrue(a.confirmsPurchase("dot_plus_weekly"))
        // A Play subscription id carries its base plan after a colon.
        assertTrue(allowance("""{"active": true, "status": "verified", "product": "dot_plus_weekly:weekly"}""").confirmsPurchase("dot_plus_weekly"))
    }

    @Test
    fun `a preview never confirms a purchase`() {
        assertFalse(allowance("""{"active": true, "status": "preview", "product": ""}""").confirmsPurchase("dot_plus_weekly"))
    }

    @Test
    fun `a promotional grant at RevenueCat never confirms a purchase`() {
        val a = allowance("""{"active": true, "status": "verified", "promotional": true, "product": "rc_promo_dot_plus_custom"}""")
        assertFalse(a.confirmsPurchase("dot_plus_weekly"))
    }

    @Test
    fun `a cached or unverified answer, another product, or an older service never confirms`() {
        assertFalse(allowance("""{"active": true, "status": "cached", "product": "dot_plus_weekly"}""").confirmsPurchase("dot_plus_weekly"))
        assertFalse(allowance("""{"active": true, "status": "verified", "product": "dot_plus_yearly"}""").confirmsPurchase("dot_plus_weekly"))
        // A service from before this change names no product: not enough to call it a purchase.
        assertFalse(allowance("""{"active": true, "status": "verified"}""").confirmsPurchase("dot_plus_weekly"))
        assertFalse(allowance("""{"active": false, "status": "verified", "product": "dot_plus_weekly"}""").confirmsPurchase("dot_plus_weekly"))
    }

    @Test
    fun `the service's refresh limit is read so confirmations are spaced past it`() {
        assertEquals(8_000L, allowance("""{"active": false}""", """, "refresh_min_s": 8.0""").refreshMinMs)
        assertEquals(Allowance.DEFAULT_REFRESH_MIN_MS, allowance("""{"active": false}""").refreshMinMs)
    }
}
