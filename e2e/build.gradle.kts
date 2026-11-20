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
        // No credentials here: run-e2e.sh builds and installs both APKs without the key and passes it, the URLs and
        // the run nonce only to `am instrument -e ...` on the device. Gradle never sees the key.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(libs.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}
