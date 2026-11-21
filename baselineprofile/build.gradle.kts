plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.skeler.scanely.baselineprofile"
    // 37.2: Compose BOM 2026.11.01 (Compose 1.13) needs 37.1 or later. Only the API surface the code
    // compiles against; targetSdk, the runtime behaviour the app opts into, is unchanged.
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

}

// Generated and benchmarked on a connected phone (rooted or API 33+); no emulator to download.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.uiautomator)
    implementation(libs.benchmark.macro)
}
