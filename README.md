# AppUpdateKit

[![](https://jitpack.io/v/junaid-apk/appupdatekit.svg)](https://jitpack.io/#junaid-apk/appupdatekit)
![minSdk](https://img.shields.io/badge/minSdk-24-brightgreen)
![License](https://img.shields.io/badge/license-MIT-blue)

Control app updates remotely in Jetpack Compose apps. Change one JSON value (for example in Firebase Remote
Config) to:

- **Force an update.** Users below a version you choose see a full-screen "Update required" page and
  can't use the app until they update.
- **Recommend an update.** Users see a dialog with **Update** and **Later**. "Later" hides it for a
  while, and you control how long and how many times.
- **Turn on maintenance mode.** Everyone sees a "We'll be back shortly" page with a **Try again** button.
- **Use Google Play in-app updates.** Background download with "restart to install", plus Play's
  full-screen update for urgent releases.

The built-in screens follow your app's theme, come in 12 languages, and can be replaced with your own
designs.

---

## Contents

1. [Requirements](#requirements)
2. [Installation](#installation)
3. [Quick start](#quick-start)
4. [The config JSON](#the-config-json)
5. [Common recipes](#common-recipes)
6. [Setting it up in Firebase Remote Config](#setting-it-up-in-firebase-remote-config)
7. [Customising the screens](#customising-the-screens)
8. [Google Play in-app updates](#google-play-in-app-updates)
9. [Testing](#testing)
10. [FAQ](#faq)

---

## Requirements

- Android **minSdk 24**
- **Jetpack Compose** with Material 3
- A way to deliver a JSON string to the app. Firebase Remote Config is the usual choice, but any source
  works. The library doesn't depend on Firebase.

## Installation

**1. Add JitPack** to `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.junaid-apk") }   // only this library comes from JitPack
        }
    }
}
```

**2. Add the dependency** to `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.junaid-apk:appupdatekit:2.0.0")
}
```

---

## Quick start

There are three steps: start the kit, give it the config, and wrap your UI.

### 1. Start the kit

In your `Application` class:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppUpdateKit.init(this, debug = BuildConfig.DEBUG)
        UpdateConfigFetcher.start(debug = BuildConfig.DEBUG)   // step 2
    }
}
```

`debug = true` enables the test tools (see [Testing](#testing)). Release builds should pass `false`.

### 2. Give it the config

Your app fetches the JSON and passes it to `AppUpdateKit.setConfig(json)`. Call it **twice**:

- **At startup, with the cached value.** Rules apply instantly, even offline.
- **After each fresh fetch.** Changes reach users without a restart.

Here's a complete helper for Firebase Remote Config. Copy it into your app:

```kotlin
import com.appupdatekit.AppUpdateKit
import com.google.firebase.remoteconfig.FirebaseRemoteConfig

object UpdateConfigFetcher {
    private const val KEY = "app_update_config"
    private var rc: FirebaseRemoteConfig? = null
    private var debug = false

    fun start(debug: Boolean) {
        if (rc != null) return
        this.debug = debug
        rc = runCatching { FirebaseRemoteConfig.getInstance() }.getOrNull()
        push()      // cached value: applies immediately
        fetch()     // fresh value: applies when it arrives
    }

    /** Hourly in release, every time in debug. force = true skips the wait. */
    fun fetch(force: Boolean = false) {
        val r = rc ?: return
        r.fetch(if (force || debug) 0 else 3600)
            .continueWithTask { r.activate() }
            .addOnCompleteListener { push() }
    }

    private fun push() = AppUpdateKit.setConfig(rc?.getString(KEY))
}
```

(Needs `implementation("com.google.firebase:firebase-config")` in your app.)

It's also worth calling `UpdateConfigFetcher.fetch()` in your Activity's `onResume`, so changes are picked
up when users return to the app.

### 3. Wrap your UI

In your Activity, put `AppUpdateGate` **inside your theme**, around your app's content:

```kotlin
setContent {
    MyAppTheme {
        AppUpdateGate(
            active = !showingSplash,                                  // optional, see below
            onRetry = { UpdateConfigFetcher.fetch(force = true) },    // maintenance "Try again"
        ) {
            MyAppContent()
        }
    }
}
```

| Parameter | What it does |
|---|---|
| `active` | `false` keeps the kit quiet, for example during a splash screen or onboarding. Your content shows normally, and any block or dialog appears once `active` becomes `true`. Default `true`. |
| `onRetry` | Runs when the user taps **Try again** on the maintenance page. Re-fetch your config here. The page closes by itself once the new config has maintenance off. |
| `requiredScreen`, `maintenanceScreen`, `recommendedDialog` | Optional. Replace a built-in screen with your own (see [Customising](#customising-the-screens)). |

The integration is now done. Control everything from the JSON.

---

## The config JSON

```json
{
  "enabled": true,
  "min_version_code": 0,
  "recommended_version_code": 0,
  "snooze_hours": 24,
  "snooze_max_count": 0,
  "maintenance": false,
  "maintenance_message": "",
  "title": "",
  "message": "",
  "store_url": ""
}
```

**Every field is optional.** Leave out anything you don't need.

| Field | Type | Default | Meaning |
|---|---|---|---|
| `enabled` | boolean | `true` | Master switch. `false` turns **everything** off, whatever the other fields say. It's handy as an emergency off button. |
| `min_version_code` | number | `0` | Users on a **versionCode below this** are blocked by the "Update required" page. `0` = off. |
| `recommended_version_code` | number | `0` | Users on a **versionCode below this** see the Update / Later dialog. `0` = off. |
| `snooze_hours` | number | `24` | After **Later**, how many hours until the dialog shows again. |
| `snooze_max_count` | number | `0` | Stop showing the dialog after the user taps **Later** this many times. `0` = keep asking. Raising `recommended_version_code` starts the count over. |
| `maintenance` | boolean | `false` | `true` shows the maintenance page to **everyone**, on any version. |
| `maintenance_message` | string | `""` | Custom text for the maintenance page. Empty = built-in text. |
| `title` | string | `""` | Custom title for the update page and dialog. Empty = built-in text. |
| `message` | string | `""` | Custom message for the update page and dialog. Empty = built-in text. |
| `store_url` | string | `""` | Where the update button sends users if Play's in-app update isn't available. Empty = your app's Play Store page. |

### How the kit decides

The kit checks these in order and shows the first one that matches:

1. `enabled` is `false` → **nothing**
2. `maintenance` is `true` → **maintenance page**
3. installed versionCode < `min_version_code` → **update required page**
4. installed versionCode < `recommended_version_code` → **update dialog** (unless snoozed)
5. otherwise → **nothing**

### Built to fail safe

If the JSON is **missing, empty or invalid**, everything is off. A typo or a failed fetch never locks your
users out.

### versionCode, not versionName

Rules compare against `versionCode`, the whole number in your `build.gradle.kts`, such as `42`, not
the `versionName` like `"2.1.0"`.

### Ship the library before you need it

The kit can only act on app versions that contain it. Versions released before you added the library
won't react to the config, so ship it in a release **before** you need to force an update.

---

## Common recipes

Assume your latest release on Play is **versionCode 42**.

**Nudge everyone to update** (dialog, at most 3 times, every 2 days):
```json
{ "recommended_version_code": 42, "snooze_hours": 48, "snooze_max_count": 3 }
```

**Force everyone to update** (for example, older versions have a serious bug):
```json
{ "min_version_code": 42 }
```

**Force very old versions, nudge the rest:**
```json
{ "min_version_code": 30, "recommended_version_code": 42 }
```

**Maintenance with your own message:**
```json
{ "maintenance": true, "maintenance_message": "We're upgrading our servers. Back by 6 PM." }
```

**Turn everything off:**
```json
{ "enabled": false }
```

---

## Setting it up in Firebase Remote Config

1. Open **Firebase console → Run → Remote Config**.
2. Click **Add parameter**:
   - **Parameter name:** `app_update_config` (or any name, as long as your fetch code uses the same one)
   - **Data type:** **JSON**
   - **Default value:** `{"enabled": true}`, which changes nothing for users
3. Click **Save**, then **Publish changes**. Nothing reaches users until you publish.

Later, edit the value using one of the [recipes](#common-recipes) and publish again. Users get it on their
next fetch: within an hour in release builds with the helper above, or right away in debug.

**Tip:** with Remote Config **conditions** you can serve different JSON by country, app version, or a
percentage of users. That's useful for rolling out a forced update gradually. Each condition holds a
complete JSON value.

---

## Customising the screens

There are three levels, from no effort to full control.

### 1. Theme (automatic)

The built-in screens use your app's `MaterialTheme`: colours, fonts, shapes and dark mode. Put
`AppUpdateGate` inside your theme and they match your app.

### 2. Text and icons (no code)

Override any of these in your app's `res/values/strings.xml`. Your text wins, in every language you
translate it into:

| String | Default |
|---|---|
| `auk_required_title` | Update required |
| `auk_required_message` | This version is no longer supported. Please update to the latest version to keep using the app. … |
| `auk_update_now` | Update now |
| `auk_recommended_title` | New version available |
| `auk_recommended_message` | A newer version with improvements and fixes is ready. Update now for the best experience. |
| `auk_update` | Update |
| `auk_later` | Later |
| `auk_maintenance_title` | Under maintenance |
| `auk_maintenance_message` | We're making some improvements and will be back shortly. … |
| `auk_try_again` | Try again |
| `auk_current_version` | Installed version %1$s |

The maintenance icon is the drawable `auk_ic_maintenance`. Add a drawable with that name to replace it.
The update page shows your **app icon** automatically.

Built-in languages: English, Arabic, German, Spanish, French, Hindi, Indonesian, Japanese,
Portuguese (Brazil), Russian, Turkish and Urdu.

### 3. Your own screens

Pass your own composables. The kit still handles **when** to show them, blocking the app, the Back button
(it closes the app), starting the update, and snoozing:

```kotlin
AppUpdateGate(
    onRetry = { UpdateConfigFetcher.fetch(force = true) },
    requiredScreen = { state, onUpdate ->
        MyUpdateScreen(version = state.installedVersionName, onUpdateClick = onUpdate)
    },
    maintenanceScreen = { state, onRetry ->
        MyMaintenanceScreen(message = state.config.maintenanceMessage, onRetryClick = onRetry)
    },
    recommendedDialog = { state, onUpdate, onLater ->
        MyUpdateDialog(onUpdateClick = onUpdate, onDismiss = onLater)
    },
) {
    MyAppContent()
}
```

Leave out any slot to keep its built-in screen. `state` gives you the parsed config
(`state.config.title`, `state.config.message`, …) and `state.installedVersionName`. The built-in screens
are public too (`AppUpdateDefaults.RequiredScreen(state, onUpdate)` and so on), so you can reuse them
inside your own layout.

### What the update button does

1. If Google Play has the update ready, it opens **Play's in-app update** screen.
2. Otherwise it opens the **Play Store page**, or `store_url` if you set one.

---

## Google Play in-app updates

`AppUpdater` is optional and separate from the config. It wraps the
[Play In-App Updates API](https://developer.android.com/guide/playcore/in-app-updates). It only works for
apps installed from Google Play.

```kotlin
class MainActivity : ComponentActivity() {
    private val updateLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppUpdater.attach(this, updateLauncher)      // after AppUpdateKit.init
        // ...
    }

    override fun onResume() {
        super.onResume()
        AppUpdater.resumeIfNeeded()                  // required by Play for interrupted updates
    }

    override fun onDestroy() {
        AppUpdater.detach()
        super.onDestroy()
    }
}
```

| Call | Use |
|---|---|
| `AppUpdater.checkFromHome()` | Call when your home screen opens. It asks Play at most every 30 minutes. If an update exists, `state` becomes `Available`. |
| `AppUpdater.check { result -> }` | A "Check for updates" button. Starts the download straight away. `result` is `Started`, `UpToDate` or `Unavailable`. |
| `AppUpdater.state` | `StateFlow<UpdateState>`: `Idle`, `Available`, `Downloading(progress)`, `ReadyToInstall`. Use it to show your own snackbar or banner. |
| `AppUpdater.startAvailableUpdate()` | Start downloading when the user taps **Update** in your snackbar. |
| `AppUpdater.completeUpdate()` | Install and restart once `state` is `ReadyToInstall`. |

Releases you upload to Play with **in-app update priority 4 or 5** skip the snackbar and go straight to
Play's full-screen update.

---

## Testing

With `AppUpdateKit.init(..., debug = true)`, you can preview every screen without touching your config.
Put these on a hidden debug screen:

```kotlin
AppUpdateKit.debugSimulate(UpdateStatus.Required)      // update required page
AppUpdateKit.debugSimulate(UpdateStatus.Recommended)   // update dialog
AppUpdateKit.debugSimulate(UpdateStatus.Maintenance)   // maintenance page
AppUpdateKit.debugSimulate(null)                       // back to the real config

AppUpdater.simulateUpdateAvailable()                   // fake Play update: snackbar → download → restart
AppUpdater.simulateUpdateAvailable(priority = 5)       // fake urgent Play update
AppUpdater.setSimulate(false)                          // back to real Play
```

Simulations only last until the app process ends, so force-stop the app to clear them. In release builds
(`debug = false`) these calls do nothing.

To test the real config, set a value in Remote Config, publish, and reopen a **debug** build. Debug builds
fetch every time, so changes show within seconds.

---

## FAQ

**Can users get past the "Update required" page?**
No. Back closes the app and there's no dismiss button. It shows again every time the app opens, until
they update or you lower `min_version_code`.

**What if the device is offline?**
The last fetched config still applies. A device that has never fetched has no config, so nothing is
blocked.

**What if I make a mistake in the JSON?**
Invalid JSON means everything is off, so you won't lock anyone out by accident. Fix it and publish again.

**Do I need Firebase?**
No. `setConfig` takes any JSON string, so you can load it from your own server, a file, or a hardcoded
value for tests.

**Does the maintenance page block users already in the app?**
Yes, once their app receives the new config (on the next fetch, or when they reopen the app). Their data
and background work are untouched. The page only covers the UI.

---

## License

MIT, see [LICENSE](LICENSE).
