package com.appupdatekit

import org.json.JSONObject

/**
 * The update policy, parsed from one JSON value (Remote Config key `app_update_config` by convention):
 * ```json
 * {
 *   "enabled": true,
 *   "min_version_code": 7,
 *   "recommended_version_code": 8,
 *   "snooze_hours": 24,
 *   "snooze_max_count": 0,
 *   "maintenance": false,
 *   "maintenance_message": "",
 *   "title": "",
 *   "message": "",
 *   "store_url": ""
 * }
 * ```
 * Every field is optional. Missing, blank or broken JSON gives [OFF], so a bad value never locks
 * users out.
 */
data class UpdateConfig(
    /** Master switch: false turns off update prompts and maintenance. */
    val enabled: Boolean = true,
    /** versionCode below this is blocked by the full-screen update page. 0 = off. */
    val minVersionCode: Long = 0,
    /** versionCode below this gets the dismissible dialog. 0 = off. */
    val recommendedVersionCode: Long = 0,
    /** Hours before the dialog returns after "Later". */
    val snoozeHours: Long = 24,
    /** Stop asking after this many "Later" taps for the same recommended version. 0 = never stop. */
    val snoozeMaxCount: Long = 0,
    /** Blocks everyone with the maintenance page (checked before the version rules). */
    val maintenance: Boolean = false,
    /** Optional texts; blank = the built-in translated text. */
    val maintenanceMessage: String = "",
    val title: String = "",
    val message: String = "",
    /** Optional; blank = this app's Play Store page. */
    val storeUrl: String = "",
) {
    companion object {
        val OFF = UpdateConfig()

        fun parse(json: String?): UpdateConfig {
            if (json.isNullOrBlank()) return OFF
            return try {
                val o = JSONObject(json)
                UpdateConfig(
                    enabled = o.optBoolean("enabled", true),
                    minVersionCode = o.optLong("min_version_code", 0).coerceAtLeast(0),
                    recommendedVersionCode = o.optLong("recommended_version_code", 0).coerceAtLeast(0),
                    snoozeHours = o.optLong("snooze_hours", 24).coerceAtLeast(0),
                    snoozeMaxCount = o.optLong("snooze_max_count", 0).coerceAtLeast(0),
                    maintenance = o.optBoolean("maintenance", false),
                    maintenanceMessage = o.optString("maintenance_message").trim(),
                    title = o.optString("title").trim(),
                    message = o.optString("message").trim(),
                    storeUrl = o.optString("store_url").trim(),
                )
            } catch (_: Exception) {
                OFF
            }
        }
    }
}
