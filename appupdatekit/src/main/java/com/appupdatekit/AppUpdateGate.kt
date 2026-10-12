package com.appupdatekit

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.delay

/**
 * Wrap the app's UI (inside its theme). Required / Maintenance replace the app with a full-screen
 * page there's no way past; Recommended shows a dismissible dialog over it.
 *
 * Each screen can be swapped for the app's own design; the kit still handles blocking, Back, the
 * update flow and snoozing. Leave a slot null to use the built-in, theme-following screen.
 *
 * @param active false while the app shouldn't be interrupted (e.g. its splash screen): the content shows
 *   as-is and any block or dialog waits until it turns true.
 * @param onRetry maintenance "Try again": re-fetch the config and call [AppUpdateKit.setConfig].
 */
@Composable
fun AppUpdateGate(
    active: Boolean = true,
    onRetry: () -> Unit = {},
    requiredScreen: (@Composable (state: AppUpdateKit.State, onUpdate: () -> Unit) -> Unit)? = null,
    maintenanceScreen: (@Composable (state: AppUpdateKit.State, onRetry: () -> Unit) -> Unit)? = null,
    recommendedDialog: (@Composable (state: AppUpdateKit.State, onUpdate: () -> Unit, onLater: () -> Unit) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val state by AppUpdateKit.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}
    val update = { startUpdate(context, launcher, state.storeUrl) }

    if (!active) {
        content()
        return
    }
    when (state.status) {
        UpdateStatus.Required -> {
            BackHandler { (context as? Activity)?.finish() }
            requiredScreen?.invoke(state, update) ?: AppUpdateDefaults.RequiredScreen(state, update)
        }
        UpdateStatus.Maintenance -> {
            BackHandler { (context as? Activity)?.finish() }
            maintenanceScreen?.invoke(state, onRetry) ?: AppUpdateDefaults.MaintenanceScreen(state, onRetry)
        }
        else -> {
            content()
            if (state.promptRecommended) {
                val onUpdate = { AppUpdateKit.dismissRecommended(); update() }
                val onLater = AppUpdateKit::dismissRecommended
                recommendedDialog?.invoke(state, onUpdate, onLater)
                    ?: AppUpdateDefaults.RecommendedDialog(state, onUpdate, onLater)
            }
        }
    }
}

/** The built-in screens; public so an app can reuse them inside its own layouts. */
object AppUpdateDefaults {

    @Composable
    fun RequiredScreen(state: AppUpdateKit.State, onUpdate: () -> Unit) {
        val context = LocalContext.current
        val icon = remember { appIcon(context) }
        BlockingPage {
            if (icon != null) {
                Image(icon, contentDescription = null, Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)))
                Spacer(Modifier.height(28.dp))
            }
            Title(state.config.title.ifBlank { stringResource(R.string.auk_required_title) })
            Spacer(Modifier.height(12.dp))
            Body(state.config.message.ifBlank { stringResource(R.string.auk_required_message) })
            Spacer(Modifier.height(36.dp))
            Button(onClick = onUpdate, Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(stringResource(R.string.auk_update_now), style = MaterialTheme.typography.titleMedium)
            }
            if (state.installedVersionName.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.auk_current_version, state.installedVersionName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    fun MaintenanceScreen(state: AppUpdateKit.State, onRetry: () -> Unit) {
        // "Try again" shows a short spinner while the app re-fetches; the gate lifts by itself once
        // the new config arrives with maintenance off.
        var checking by remember { mutableStateOf(false) }
        LaunchedEffect(checking) {
            if (checking) { delay(2500); checking = false }
        }
        BlockingPage {
            Box(
                Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.auk_ic_maintenance), contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp),
                )
            }
            Spacer(Modifier.height(28.dp))
            Title(stringResource(R.string.auk_maintenance_title))
            Spacer(Modifier.height(12.dp))
            Body(state.config.maintenanceMessage.ifBlank { stringResource(R.string.auk_maintenance_message) })
            Spacer(Modifier.height(36.dp))
            OutlinedButton(
                onClick = { checking = true; onRetry() },
                enabled = !checking,
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                if (checking) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.auk_try_again), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    @Composable
    fun RecommendedDialog(state: AppUpdateKit.State, onUpdate: () -> Unit, onLater: () -> Unit) {
        AlertDialog(
            onDismissRequest = onLater,
            title = { Text(state.config.title.ifBlank { stringResource(R.string.auk_recommended_title) }) },
            text = { Text(state.config.message.ifBlank { stringResource(R.string.auk_recommended_message) }) },
            confirmButton = { Button(onClick = onUpdate) { Text(stringResource(R.string.auk_update)) } },
            dismissButton = { TextButton(onClick = onLater) { Text(stringResource(R.string.auk_later)) } },
        )
    }

    @Composable
    private fun BlockingPage(body: @Composable ColumnScope.() -> Unit) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier
                        .widthIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = body,
                )
            }
        }
    }

    @Composable
    private fun Title(text: String) = Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
    )

    @Composable
    private fun Body(text: String) = Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

private fun appIcon(context: Context): ImageBitmap? = runCatching {
    context.packageManager.getApplicationIcon(context.packageName).toBitmap(192, 192).asImageBitmap()
}.getOrNull()

/** Play's in-app (immediate) update when Play offers one, otherwise the Store page. */
private fun startUpdate(context: Context, launcher: ActivityResultLauncher<IntentSenderRequest>, storeUrl: String) {
    val manager = runCatching { AppUpdateManagerFactory.create(context) }.getOrNull()
        ?: return openStore(context, storeUrl)
    manager.appUpdateInfo
        .addOnSuccessListener { info ->
            val available = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE ||
                info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
            val started = available && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) && runCatching {
                manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build())
            }.getOrDefault(false)
            if (!started) openStore(context, storeUrl)
        }
        .addOnFailureListener { openStore(context, storeUrl) }
}

private fun openStore(context: Context, storeUrl: String) {
    val playPage = "https://play.google.com/store/apps/details?id=${context.packageName}"
    val intents = buildList {
        if (storeUrl == playPage) {
            add(Intent(Intent.ACTION_VIEW, "market://details?id=${context.packageName}".toUri())
                .setPackage("com.android.vending"))
        }
        add(Intent(Intent.ACTION_VIEW, storeUrl.toUri()))
    }
    for (intent in intents) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
}
