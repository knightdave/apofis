plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "xyz.rycerz.apofis"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        applicationId = "xyz.rycerz.apofis"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = System.getenv("APOFIS_VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("APOFIS_VERSION_NAME") ?: "0.1.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // End-to-end key for tools/emulator/run-e2e.sh: environment only, never a file. The URL comes from
        // -Pandroid.testInstrumentationRunnerArguments.apofisUrl=...
        System.getenv("APOFIS_KEY")?.let { testInstrumentationRunnerArguments["apofisKey"] = it }
    }

    // Release signing needs all four variables; with none set the release APK is unsigned.
    val signingVars = listOf("APOFIS_KEYSTORE_FILE", "APOFIS_KEYSTORE_PASSWORD", "APOFIS_KEY_ALIAS", "APOFIS_KEY_PASSWORD")
    val signingEnv = signingVars.associateWith { System.getenv(it)?.takeIf(String::isNotEmpty) }
    val missingSigning = signingEnv.filterValues { it == null }.keys
    if (missingSigning.isNotEmpty() && missingSigning.size < signingVars.size) {
        throw GradleException("Release signing is partly configured. Missing or empty: ${missingSigning.joinToString()}")
    }
    val signRelease = missingSigning.isEmpty()
    signingConfigs {
        if (signRelease) {
            create("release") {
                storeFile = file(signingEnv.getValue("APOFIS_KEYSTORE_FILE")!!)
                storePassword = signingEnv["APOFIS_KEYSTORE_PASSWORD"]
                keyAlias = signingEnv["APOFIS_KEY_ALIAS"]
                keyPassword = signingEnv["APOFIS_KEY_PASSWORD"]
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signRelease) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    // Third-party license texts ship verbatim from the repository's licenses/ directory.
    sourceSets.getByName("main").assets.directories.add(rootProject.file("licenses").path)

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// The end-to-end key is a runner argument, so a tracked connected test task would store it in Gradle's execution history.
tasks.matching { it.name.startsWith("connected") }.configureEach {
    doNotTrackState("Instrumentation arguments may hold credentials")
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.work.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.espresso.core)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
