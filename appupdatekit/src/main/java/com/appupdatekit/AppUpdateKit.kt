package com.appupdatekit

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Entry point. The app owns fetching the config (Remote Config or anything else) and hands the JSON
 * in with [setConfig]; the kit decides and [AppUpdateGate] shows the screens.
 *
 * ```kotlin
 * AppUpdateKit.init(this, debug = BuildConfig.DEBUG)
 * AppUpdateKit.setConfig(remoteConfig.getString("app_update_config"))   // cached value at startup
 * remoteConfig.fetchAndActivate().addOnCompleteListener {                 // then the fresh one
 *     AppUpdateKit.setConfig(remoteConfig.getString("app_update_config"))
 * }
 * ```
 */
object AppUpdateKit {
    private const val PREFS = "app_update_kit"
    private const val PREF_SNOOZE_FOR = "snooze_for"
    private const val PREF_SNOOZE_COUNT = "snooze_count"
    private const val PREF_SNOOZE_AT = "snooze_at"

    data class State(
        val status: UpdateStatus = UpdateStatus.Ok,
        /** True when the recommended dialog should be on screen now (not snoozed). */
        val promptRecommended: Boolean = false,
        val config: UpdateConfig = UpdateConfig.OFF,
        /** [UpdateConfig.storeUrl], or this app's Play Store page. */
        val storeUrl: String = "",
        val installedVersionName: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Debug builds: a status forced from Developer tools, cleared by [debugSimulate] (null) or a restart. */
    private var debugOverride: UpdateStatus? = null

    private var prefs: SharedPreferences? = null
    private var config = UpdateConfig.OFF
    private var packageName = ""

    var installedVersionCode = 0L
        private set
    var installedVersionName = ""
        private set
    var isDebug = false
        private set

    fun init(context: Context, debug: Boolean) {
        if (prefs != null) return
        val app = context.applicationContext
        packageName = app.packageName
        isDebug = debug
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        runCatching {
            val info = app.packageManager.getPackageInfo(packageName, 0)
            installedVersionCode = PackageInfoCompat.getLongVersionCode(info)
            installedVersionName = info.versionName.orEmpty()
        }
        recompute()
    }

    /** Pass the raw JSON whenever the app has a value (cached at startup, then after each fetch). */
    fun setConfig(json: String?) {
        config = UpdateConfig.parse(json)
        recompute()
    }

    /** "Later" on the recommended dialog. */
    fun dismissRecommended() {
        if (debugOverride == UpdateStatus.Recommended) {
            debugOverride = null
        } else {
            val next = readSnooze().next(config.recommendedVersionCode, System.currentTimeMillis())
            prefs?.edit()
                ?.putLong(PREF_SNOOZE_FOR, next.forVersion)
                ?.putLong(PREF_SNOOZE_COUNT, next.count)
                ?.putLong(PREF_SNOOZE_AT, next.at)
                ?.apply()
        }
        recompute()
    }

    fun debugSimulate(status: UpdateStatus?) {
        if (!isDebug) return
        debugOverride = status
        recompute()
    }

    private fun readSnooze(): UpdatePolicy.Snooze = prefs?.let {
        UpdatePolicy.Snooze(it.getLong(PREF_SNOOZE_FOR, 0), it.getLong(PREF_SNOOZE_COUNT, 0), it.getLong(PREF_SNOOZE_AT, 0))
    } ?: UpdatePolicy.Snooze()

    private fun recompute() {
        val override = debugOverride
        val status = override ?: UpdatePolicy.status(installedVersionCode, config)
        val prompt = status == UpdateStatus.Recommended &&
            (override != null || UpdatePolicy.shouldPromptRecommended(config, readSnooze(), System.currentTimeMillis()))
        _state.value = State(
            status = status,
            promptRecommended = prompt,
            config = config,
            storeUrl = config.storeUrl.ifBlank { "https://play.google.com/store/apps/details?id=$packageName" },
            installedVersionName = installedVersionName,
        )
    }
}
