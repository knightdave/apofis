plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
