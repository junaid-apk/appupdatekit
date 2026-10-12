package com.appupdatekit

enum class UpdateStatus { Ok, Recommended, Required, Maintenance }

/** Pure decision logic, unit tested. */
object UpdatePolicy {
    private const val HOUR_MS = 60L * 60 * 1000

    fun status(installed: Long, config: UpdateConfig): UpdateStatus = when {
        !config.enabled -> UpdateStatus.Ok
        config.maintenance -> UpdateStatus.Maintenance
        config.minVersionCode > 0 && installed < config.minVersionCode -> UpdateStatus.Required
        config.recommendedVersionCode > 0 && installed < config.recommendedVersionCode -> UpdateStatus.Recommended
        else -> UpdateStatus.Ok
    }

    /**
     * Whether the recommended dialog should show now. A newer recommended version than the one the
     * user snoozed starts over; otherwise it waits [UpdateConfig.snoozeHours] after each "Later" and
     * stops for good after [UpdateConfig.snoozeMaxCount] of them (0 = keeps asking).
     */
    fun shouldPromptRecommended(config: UpdateConfig, snooze: Snooze, now: Long): Boolean {
        if (config.recommendedVersionCode > snooze.forVersion) return true
        if (config.snoozeMaxCount > 0 && snooze.count >= config.snoozeMaxCount) return false
        return now - snooze.at >= config.snoozeHours * HOUR_MS
    }

    /** What the user did with the dialog: snoozed [count] times for [forVersion], last at [at]. */
    data class Snooze(val forVersion: Long = 0, val count: Long = 0, val at: Long = 0) {
        fun next(recommended: Long, now: Long) =
            Snooze(recommended, if (recommended == forVersion) count + 1 else 1, now)
    }
}
