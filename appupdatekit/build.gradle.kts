// AppUpdateKit 2: force / recommended update, maintenance and Play in-app updates from one JSON
// config the app passes in. Published through JitPack (see README.md).
plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.compose)
  `maven-publish`
}

android {
  namespace = "com.appupdatekit"
  compileSdk = 36

  defaultConfig {
    minSdk = 24
    consumerProguardFiles("consumer-rules.pro")
  }

  buildTypes {
    // Apps add qa/qaRelease build types; they fall back to release here.
    release { isMinifyEnabled = false }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures { compose = true }
  publishing {
    singleVariant("release") { withSourcesJar() }
  }
}

// JitPack builds the tag and serves it as com.github.<owner>:appupdatekit:<tag>.
publishing {
  publications {
    register<MavenPublication>("release") {
      groupId = "com.github.junaid-apk"
      artifactId = "appupdatekit"
      version = "2.0.0"
      afterEvaluate { from(components["release"]) }
    }
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.play.app.update.ktx)
  testImplementation(libs.junit)
  // org.json is an Android stub in plain JVM tests; use the real one.
  testImplementation(libs.org.json)
}
