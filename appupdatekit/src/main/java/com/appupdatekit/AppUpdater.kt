package com.appupdatekit

import android.app.Activity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface UpdateState {
    data object Idle : UpdateState
    /** A newer version is on Play and the user hasn't started it yet. */
    data object Available : UpdateState
    /** progress is 0f..1f, or null while Play hasn't reported sizes yet. */
    data class Downloading(val progress: Float?) : UpdateState
    data object ReadyToInstall : UpdateState
}

/**
 * Google Play in-app updates. Home checks Play each time it opens (throttled); an available update
 * shows an "Update" snackbar that starts the flexible flow, then a Restart snackbar once downloaded.
 * Releases published with in-app update priority >= 4 go straight to the full-screen immediate flow.
 *
 * Real updates only work for Play-installed builds (internal testing / internal app sharing). Debug
 * builds (AppUpdateKit.init(debug = true)) can switch to [FakeAppUpdateManager] from Developer tools.
 * Call AppUpdateKit.init before attach.
 */
object AppUpdater {
    private const val HOME_CHECK_INTERVAL_MS = 30L * 60 * 1000
    private const val IMMEDIATE_PRIORITY = 4

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private var realManager: AppUpdateManager? = null
    private var fakeManager: FakeAppUpdateManager? = null
    private var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var lastHomeCheck = 0L

    /** Debug builds only: route everything through the fake manager. */
    var simulate: Boolean = false
        private set

    private val manager: AppUpdateManager? get() = if (simulate) fakeManager else realManager

    private val listener = InstallStateUpdatedListener { s ->
        _state.value = when (s.installStatus()) {
            InstallStatus.PENDING, InstallStatus.DOWNLOADING -> UpdateState.Downloading(
                s.totalBytesToDownload().takeIf { it > 0 }?.let { s.bytesDownloaded().toFloat() / it }
            )
            InstallStatus.DOWNLOADED -> UpdateState.ReadyToInstall
            else -> UpdateState.Idle
        }
    }

    /** Call from Activity.onCreate with a launcher registered via StartIntentSenderForResult. */
    fun attach(activity: Activity, resultLauncher: ActivityResultLauncher<IntentSenderRequest>) {
        launcher = resultLauncher
        if (realManager == null) {
            realManager = AppUpdateManagerFactory.create(activity.applicationContext).also { it.registerListener(listener) }
        }
        if (AppUpdateKit.isDebug && fakeManager == null) {
            fakeManager = FakeAppUpdateManager(activity.applicationContext).also { it.registerListener(listener) }
        }
    }

    fun detach() {
        launcher = null
    }

    /** Home screen opened: ask Play whether there's a newer version (at most every 30 minutes). */
    fun checkFromHome() {
        val now = System.currentTimeMillis()
        if (now - lastHomeCheck < HOME_CHECK_INTERVAL_MS) return
        lastHomeCheck = now
        check(userInitiated = false) {}
    }

    /**
     * Manual check from Settings starts the flexible flow directly; the Home check only surfaces the
     * Update snackbar (except for high-priority releases, which always go immediate).
     */
    fun check(userInitiated: Boolean = true, onResult: (CheckResult) -> Unit) {
        val m = manager ?: return onResult(CheckResult.Unavailable)
        m.appUpdateInfo
            .addOnSuccessListener { info ->
                when {
                    info.installStatus() == InstallStatus.DOWNLOADED -> {
                        _state.value = UpdateState.ReadyToInstall
                        onResult(CheckResult.Started)
                    }
                    info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ->
                        startFlow(info, AppUpdateType.IMMEDIATE, onResult)
                    info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE -> {
                        if (_state.value == UpdateState.Available) _state.value = UpdateState.Idle
                        onResult(CheckResult.UpToDate)
                    }
                    info.updatePriority() >= IMMEDIATE_PRIORITY && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) ->
                        startFlow(info, AppUpdateType.IMMEDIATE, onResult)
                    !info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> onResult(CheckResult.UpToDate)
                    userInitiated -> startFlow(info, AppUpdateType.FLEXIBLE, onResult)
                    else -> {
                        if (_state.value == UpdateState.Idle) _state.value = UpdateState.Available
                        onResult(CheckResult.Started)
                    }
                }
            }
            .addOnFailureListener { onResult(CheckResult.Unavailable) }
    }

    /** "Update" on the snackbar. */
    fun startAvailableUpdate() {
        val m = manager ?: return
        m.appUpdateInfo.addOnSuccessListener { info ->
            if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
                startFlow(info, AppUpdateType.FLEXIBLE) { if (it != CheckResult.Started) _state.value = UpdateState.Idle }
                // The fake manager shows no Play dialog; accept and download on the user's behalf.
                if (simulate) fakeManager?.run { userAcceptsUpdate(); downloadStarts(); simulateProgress(this) }
            } else {
                _state.value = UpdateState.Idle
            }
        }
    }

    /** Resume an immediate update the user left mid-way (Play requires this in onResume). */
    fun resumeIfNeeded() {
        val m = manager ?: return
        m.appUpdateInfo.addOnSuccessListener { info ->
            when {
                info.installStatus() == InstallStatus.DOWNLOADED -> _state.value = UpdateState.ReadyToInstall
                info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ->
                    startFlow(info, AppUpdateType.IMMEDIATE) {}
            }
        }
    }

    fun completeUpdate() {
        if (simulate) {
            fakeManager?.installCompletes()
            _state.value = UpdateState.Idle
        } else {
            manager?.completeUpdate()
        }
    }

    private fun startFlow(info: AppUpdateInfo, type: Int, onResult: (CheckResult) -> Unit) {
        val m = manager
        val l = launcher
        if (m == null || l == null) return onResult(CheckResult.Unavailable)
        val started = runCatching { m.startUpdateFlowForResult(info, l, AppUpdateOptions.newBuilder(type).build()) }
            .getOrDefault(false)
        onResult(if (started) CheckResult.Started else CheckResult.Unavailable)
    }

    // ---- Debug-only simulator (Settings > Developer tools) ----

    fun setSimulate(on: Boolean) {
        if (!AppUpdateKit.isDebug) return
        simulate = on
        lastHomeCheck = 0
        _state.value = UpdateState.Idle
    }

    /** Pretend Play has a newer version; the next Home visit (or this call) shows the Update snackbar. */
    fun simulateUpdateAvailable(priority: Int = 0) {
        if (!AppUpdateKit.isDebug) return
        setSimulate(true)
        fakeManager?.apply {
            setUpdateAvailable(AppUpdateKit.installedVersionCode.toInt() + 1)
            setUpdatePriority(priority)
        }
        check(userInitiated = false) {}
    }

    private fun simulateProgress(fake: FakeAppUpdateManager) {
        val total = 10_000_000L
        fake.setTotalBytesToDownload(total)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        (1..10).forEach { step ->
            handler.postDelayed({
                fake.setBytesDownloaded(total * step / 10)
                if (step == 10) fake.downloadCompletes()
            }, step * 400L)
        }
    }

    enum class CheckResult { Started, UpToDate, Unavailable }
}
