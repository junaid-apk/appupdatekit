# AppUpdateKit 2 (`:appupdatekit`)

Everything about app updates in one module: forced update, recommended update, maintenance mode, and
Google Play in-app updates (flexible + immediate). The app fetches one JSON config (from Remote Config
or anywhere else) and passes it in; the kit decides and shows the screens.

## Install (JitPack)
```kotlin
// settings.gradle.kts → dependencyResolutionManagement.repositories
maven { url = uri("https://jitpack.io") }

// app/build.gradle.kts
implementation("com.github.junaid-apk:appupdatekit:2.0.0")
```
minSdk 24, Jetpack Compose. The app keeps its own Firebase / Remote Config; the kit has no Firebase dependency.

## Config JSON (Remote Config key `app_update_config`, type JSON)
```json
{
  "enabled": true,
  "min_version_code": 7,
  "recommended_version_code": 8,
  "snooze_hours": 24,
  "snooze_max_count": 0,
  "maintenance": false,
  "maintenance_message": "",
  "title": "",
  "message": "",
  "store_url": ""
}
```
| Field | Default | Effect |
|---|---|---|
| `enabled` | `true` | `false` turns everything off (including maintenance) |
| `min_version_code` | `0` | versionCode below this → full-screen "Update required", no way past |
| `recommended_version_code` | `0` | versionCode below this → dialog with Update / Later |
| `snooze_hours` | `24` | hours before the dialog returns after "Later" |
| `snooze_max_count` | `0` | stop asking after this many "Later" taps for the same version (0 = keep asking); a higher `recommended_version_code` starts over |
| `maintenance` | `false` | full-screen maintenance page for everyone; checked before the version rules |
| `maintenance_message`, `title`, `message` | `""` | optional custom text; blank = built-in text in 12 languages |
| `store_url` | `""` | optional; blank = the app's Play Store page |

Every field is optional. Missing, empty or invalid JSON = everything off, so a mistake never locks users out.
Compare against **versionCode** (`app/build.gradle.kts`), not versionName.

Update button: Play's immediate in-app update when Play offers one, otherwise opens the Store.

## Setup in an app
1. Add the dependency (above), plus `firebase-config` if the config comes from Remote Config.
2. In `Application.onCreate`: `AppUpdateKit.init(this, debug = BuildConfig.DEBUG)`, then feed the JSON:
   ```kotlin
   val rc = FirebaseRemoteConfig.getInstance()
   AppUpdateKit.setConfig(rc.getString("app_update_config"))           // cached value, instantly
   rc.fetchAndActivate().addOnCompleteListener {                        // fresh value
       AppUpdateKit.setConfig(rc.getString("app_update_config"))
   }
   ```
   (AutoCare / Expense Manager do this in `RemoteConfigSync`, which also re-fetches on resume.)
3. Wrap the UI inside the app theme:
   ```kotlin
   AppUpdateGate(
       active = !showingSplash,                       // nothing over the splash; prompts wait for it
       onRetry = { RemoteConfigSync.fetch(force = true) },
   ) { AppContent() }
   ```
4. Play in-app updates: `AppUpdater.attach(activity, launcher)` in onCreate, `resumeIfNeeded()` in onResume,
   `detach()` in onDestroy; `checkFromHome()`, `check {}`, `state` for the app's own snackbar.
5. Developer tools (debug): `AppUpdateKit.debugSimulate(UpdateStatus.Required | Recommended | Maintenance | null)`
   and `AppUpdater.simulateUpdateAvailable()`. Simulations clear on force-stop.

## Custom design per app
- **Automatic:** the built-in screens use the app's `MaterialTheme` (colours, fonts, shapes, dark mode).
- **Text / icon:** define a string or drawable with the same name in the app (`auk_required_title`,
  `auk_ic_maintenance`, …); the app's version wins.
- **Own screens:** pass any of the slots; the kit still handles blocking, Back, updating and snooze:
  ```kotlin
  AppUpdateGate(
      onRetry = { ... },
      requiredScreen = { state, onUpdate -> MyUpdateScreen(state, onUpdate) },
      maintenanceScreen = { state, onRetry -> MyMaintenance(onRetry) },
      recommendedDialog = { state, onUpdate, onLater -> MyDialog(onUpdate, onLater) },
  ) { AppContent() }
  ```
  `AppUpdateDefaults.RequiredScreen(...)` etc. are public if you want to reuse parts.

Only installs that contain this module can be blocked, so ship it before you need it.

## Releasing a new version
Bump `version` in `appupdatekit/build.gradle.kts`, commit, tag the same version (`git tag 2.0.1`) and push the
tag; JitPack builds it on first request. Version 1.x (XML screens, different JSON) is unrelated to 2.x.
