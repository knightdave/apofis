// End-to-end tests (tools/emulator/run-e2e.sh). A separate, self-instrumenting test APK: the tests run in their own
// process, so they can clear, force-stop and relaunch the app without killing themselves.
plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "xyz.rycerz.apofis.e2e"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }
    buildToolsVersion = libs.versions.buildTools.get()
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The API key comes from the environment only, never a file. The URLs come from
        // -Pandroid.testInstrumentationRunnerArguments.apofisUrl=... and ...apofisDirectUrl=...
        System.getenv("APOFIS_KEY")?.let { testInstrumentationRunnerArguments["apofisKey"] = it }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// The key is a runner argument, so a tracked connected test task would store it in Gradle's execution history.
tasks.matching { it.name.startsWith("connected") }.configureEach {
    doNotTrackState("Instrumentation arguments may hold credentials")
}

dependencies {
    implementation(libs.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}
