package com.appupdatekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {

    private val hour = 60L * 60 * 1000
    private val t = 10_000_000_000L

    // ---- Parsing ----

    @Test fun blankOrBrokenJsonIsOff() {
        assertEquals(UpdateConfig.OFF, UpdateConfig.parse(null))
        assertEquals(UpdateConfig.OFF, UpdateConfig.parse(""))
        assertEquals(UpdateConfig.OFF, UpdateConfig.parse("{not json"))
        assertEquals(UpdateConfig.OFF, UpdateConfig.parse("[1,2]"))
    }

    @Test fun fullJsonParses() {
        val c = UpdateConfig.parse(
            """{"enabled":true,"min_version_code":7,"recommended_version_code":8,"snooze_hours":12,
               "snooze_max_count":3,"maintenance":false,"maintenance_message":" Back at 5 ",
               "title":"T","message":"M","store_url":"https://x.y"}"""
        )
        assertEquals(UpdateConfig(true, 7, 8, 12, 3, false, "Back at 5", "T", "M", "https://x.y"), c)
    }

    @Test fun missingFieldsUseDefaults() {
        val c = UpdateConfig.parse("""{"min_version_code":5}""")
        assertEquals(UpdateConfig.OFF.copy(minVersionCode = 5), c)
    }

    @Test fun negativeNumbersClampToZero() {
        assertEquals(0L, UpdateConfig.parse("""{"min_version_code":-3}""").minVersionCode)
    }

    @Test fun numbersAsStringsStillWork() {
        assertEquals(7L, UpdateConfig.parse("""{"min_version_code":"7"}""").minVersionCode)
    }

    // ---- Status ----

    @Test fun offNeverBlocks() {
        assertEquals(UpdateStatus.Ok, UpdatePolicy.status(1, UpdateConfig.OFF))
    }

    @Test fun belowMinIsRequired() {
        assertEquals(UpdateStatus.Required, UpdatePolicy.status(5, UpdateConfig(minVersionCode = 6)))
    }

    @Test fun requiredWinsOverRecommended() {
        assertEquals(UpdateStatus.Required, UpdatePolicy.status(5, UpdateConfig(minVersionCode = 6, recommendedVersionCode = 8)))
    }

    @Test fun atThresholdsIsOk() {
        assertEquals(UpdateStatus.Ok, UpdatePolicy.status(6, UpdateConfig(minVersionCode = 6, recommendedVersionCode = 6)))
    }

    @Test fun belowRecommendedOnly() {
        assertEquals(UpdateStatus.Recommended, UpdatePolicy.status(6, UpdateConfig(minVersionCode = 4, recommendedVersionCode = 7)))
    }

    @Test fun maintenanceWinsOverEverything() {
        assertEquals(UpdateStatus.Maintenance, UpdatePolicy.status(99, UpdateConfig(maintenance = true, minVersionCode = 100)))
    }

    @Test fun disabledTurnsOffMaintenanceToo() {
        assertEquals(UpdateStatus.Ok, UpdatePolicy.status(1, UpdateConfig(enabled = false, maintenance = true, minVersionCode = 9)))
    }

    // ---- Snooze ----

    private val rec = UpdateConfig(recommendedVersionCode = 8, snoozeHours = 24)

    @Test fun promptsFirstTime() {
        assertTrue(UpdatePolicy.shouldPromptRecommended(rec, UpdatePolicy.Snooze(), t))
    }

    @Test fun snoozedForConfiguredHours() {
        val s = UpdatePolicy.Snooze().next(8, t)
        assertFalse(UpdatePolicy.shouldPromptRecommended(rec, s, t + 23 * hour))
        assertTrue(UpdatePolicy.shouldPromptRecommended(rec, s, t + 24 * hour))
        assertFalse(UpdatePolicy.shouldPromptRecommended(rec.copy(snoozeHours = 48), s, t + 24 * hour))
    }

    @Test fun stopsAfterMaxCount() {
        val c = rec.copy(snoozeMaxCount = 2)
        val once = UpdatePolicy.Snooze().next(8, t)
        assertTrue(UpdatePolicy.shouldPromptRecommended(c, once, t + 25 * hour))
        val twice = once.next(8, t + 25 * hour)
        assertEquals(2L, twice.count)
        assertFalse(UpdatePolicy.shouldPromptRecommended(c, twice, t + 1000 * hour))
    }

    @Test fun newerRecommendedStartsOver() {
        val c = rec.copy(snoozeMaxCount = 1)
        val snoozed = UpdatePolicy.Snooze().next(8, t)
        assertTrue(UpdatePolicy.shouldPromptRecommended(c.copy(recommendedVersionCode = 9), snoozed, t + 1000))
        assertEquals(1L, snoozed.next(9, t).count)
    }
}
