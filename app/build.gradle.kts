import java.io.File
import java.net.URI
import java.util.Properties

// Google test AdMob IDs — never serve real ads.
val admobTestAppId = "ca-app-pub-3940256099942544~3347511713"
val admobTestRewardedUnitId = "ca-app-pub-3940256099942544/5224354917"

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.room)
    // google-services 4.5.0 (latest) fails on AGP 9 — it links against the removed
    // com.android.build.api.variant.Variant. firebaseResValues() below emits the same
    // resources the plugin would, which is all FirebaseInitProvider actually reads.
}

// local.properties (gitignored): keys, signing, real AdMob ids.
val secretsProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// The Scanly backend (Cloudflare Worker). This is a public URL, not a secret — the secret it
// guards is the Mistral key, which now lives only on the Worker. Overridable from
// local.properties (or -PSCANLY_API_BASE_URL, which wins) so a debug build can point at
// `wrangler dev`. A release build refuses that override — see backendUrlProblem().
fun scanlyApiBaseUrl(): String =
    providers.gradleProperty("SCANLY_API_BASE_URL").orNull
        ?: secretsProps.getProperty("SCANLY_API_BASE_URL")
        ?: "https://scanly-api.webforge-skeler.workers.dev/"

/**
 * Why a release APK must not carry [url], or null when it may.
 *
 * On a phone, a loopback address is the phone itself: a release built while local.properties still
 * pointed at `wrangler dev` refuses every Worker call, and the app reports each one as a network
 * problem. 10.0.2.2 is the emulator's alias for the host's loopback, so it is refused with it.
 */
fun backendUrlProblem(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull()
    val host = uri?.host?.lowercase()?.removePrefix("[")?.removeSuffix("]")
    val loopback = host != null && (
        host == "localhost" || host.endsWith(".localhost") || host.startsWith("127.") ||
            host == "::1" || host == "0:0:0:0:0:0:0:1" || host == "0.0.0.0" || host == "10.0.2.2"
        )
    val reason = when {
        host == null -> "is not a URL with a host"
        uri.scheme != "https" -> "is not https"
        loopback -> "points at this machine, which on a phone is the phone itself"
        else -> return null
    }
    return "SCANLY_API_BASE_URL ($url) $reason. Release builds must use the deployed Worker: " +
        "remove the override from local.properties, or pass -PSCANLY_API_BASE_URL=https://…"
}


val firebaseConfigFile = rootProject.file("app/google-services.json")

// Every ABI Scanly ships. Anything missing here is a device class Play marks the app incompatible
// with, so the bundle is only ever built from the full set; see the guard at the foot of the file.
val fullReleaseAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

// -PreleaseAbi=arm64-v8a cuts a sideload APK from 218 MB to one ABI. Direct distribution only.
val releaseAbis: List<String> = (project.findProperty("releaseAbi") as String?)
    ?.split(",")
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?: fullReleaseAbis

// google-services.json is gitignored, so CI has no Firebase config at all. Returning nothing left
// R.string.default_web_client_id undefined and every CI job failed at compileDebugKotlin. These
// let the project build without it; `assembleRelease`/`bundleRelease` refuse them below, so a
// placeholder configuration can never reach a shipped artifact.
val firebasePlaceholders = mapOf(
    "google_app_id" to "1:000000000000:android:0000000000000000",
    "google_api_key" to "no-firebase-config",
    "google_crash_reporting_api_key" to "no-firebase-config",
    "gcm_defaultSenderId" to "000000000000",
    "project_id" to "no-firebase-config",
    "default_web_client_id" to "000000000000-placeholder.apps.googleusercontent.com"
)

// Stand-in for the google-services plugin (see the plugins block): the strings
// FirebaseInitProvider reads at startup, plus default_web_client_id for Credential Manager.
// Firebase Android "keys" are client identifiers shipped in every APK, not secrets.
@Suppress("UNCHECKED_CAST")
fun firebaseResValues(applicationId: String): Map<String, String> {
    val f = firebaseConfigFile
    if (!f.exists()) return firebasePlaceholders

    val root = groovy.json.JsonSlurper().parse(f) as Map<String, Any>
    val projectInfo = root["project_info"] as Map<String, Any>
    val clients = root["client"] as List<Map<String, Any>>

    val client = clients.firstOrNull { c ->
        val info = (c["client_info"] as Map<String, Any>)["android_client_info"] as Map<String, Any>
        info["package_name"] == applicationId
    } ?: throw GradleException(
        "google-services.json has no client for '$applicationId'. Register the app in Firebase " +
            "(firebase apps:create ANDROID --package-name $applicationId) and re-download the file."
    )

    val oauthClients = client["oauth_client"] as List<Map<String, Any>>
    // client_type 3 is the web client; Credential Manager needs it as the serverClientId, and it
    // only exists once Google sign-in is enabled on the project.
    val webClientId = oauthClients.firstOrNull { it["client_type"] == 3 }?.get("client_id") as String?
        ?: throw GradleException(
            "No web OAuth client in google-services.json — enable Google sign-in in the Firebase " +
                "console, then re-download the file."
        )

    val apiKey = (client["api_key"] as List<Map<String, Any>>).first()["current_key"] as String

    return buildMap {
        put("google_app_id", (client["client_info"] as Map<String, Any>)["mobilesdk_app_id"] as String)
        put("google_api_key", apiKey)
        put("google_crash_reporting_api_key", apiKey)
        put("gcm_defaultSenderId", projectInfo["project_number"] as String)
        put("project_id", projectInfo["project_id"] as String)
        put("default_web_client_id", webClientId)
        (projectInfo["storage_bucket"] as String?)?.let { put("google_storage_bucket", it) }
    }
}

android {
    namespace = "com.skeler.scanely"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.skeler.scanely"
        minSdk = 24
        targetSdk = 37
        versionCode = 23
        versionName = "3.6.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // No provider key is compiled into the app. The Mistral key is a Worker secret and the
        // app authenticates to the backend with a Firebase ID token.
        buildConfigField("String", "SCANLY_API_BASE_URL", "\"${scanlyApiBaseUrl()}\"")

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // qpdf pulls in the CLI, docs and fuzzers by default; none of that can link on
                // Android. Crypto is forced to qpdf's own implementation so the build needs
                // neither OpenSSL nor GnuTLS in the NDK sysroot.
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DBUILD_SHARED_LIBS=OFF"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // Must name a version present in $sdk.dir/cmake. Keep in step with CI's installed SDK.
            version = "3.31.6"
        }
    }

    sourceSets.getByName("test").resources.srcDir(rootProject.file("contracts"))

    testOptions {
        // Mockito instruments bootstrap classes; CDS is unavailable in this test JVM.
        unitTests.all { it.jvmArgs("-Xshare:off") }
    }

    lint {
        baseline = file("lint-baseline.xml")
    }

    // Play delivers one ABI/density/language slice per device. The splits DSL is ignored for
    // bundles; which ABIs exist to be split comes from each buildType's ndk.abiFilters.
    bundle {
        abi { enableSplit = true }
        density { enableSplit = true }
        language { enableSplit = true }
    }

    signingConfigs {
        create("release") {
            storeFile = file(secretsProps.getProperty("storeFile") ?: "keystore.jks")
            storePassword = secretsProps.getProperty("storePassword")
            keyAlias = secretsProps.getProperty("keyAlias")
            keyPassword = secretsProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            // Side-by-side with release-signed production install.
            applicationIdSuffix = ".debug"
            // One ABI keeps debug builds small; -PdebugAbi=x86_64 for an emulator.
            ndk {
                abiFilters += (project.findProperty("debugAbi") as String?)
                    ?.takeUnless { it.isBlank() } ?: "arm64-v8a"
            }
            manifestPlaceholders["admobAppId"] = admobTestAppId
            buildConfigField("String", "ADMOB_REWARDED_AD_UNIT_ID", "\"$admobTestRewardedUnitId\"")

            // Debug installs as com.skeler.scanely.debug, which is a separate Firebase app.
            for ((name, value) in firebaseResValues("com.skeler.scanely.debug")) {
                resValue("string", name, value)
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")

            // abiFilters restricts what enters the AAB as well as the APK; the bundle DSL only
            // decides how what is already there gets split.
            ndk {
                abiFilters += releaseAbis
                // Pinned rather than left to AGP's default, which has moved between versions and
                // silently changes what lands in BUNDLE-METADATA. SYMBOL_TABLE is what makes a
                // native crash in libscanlypdf readable in Play Console; FULL adds DWARF nobody
                // reads here. Neither is downloaded to a device.
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Real AdMob from local.properties; test ids as fallback.
            manifestPlaceholders["admobAppId"] =
                secretsProps.getProperty("ADMOB_APP_ID") ?: admobTestAppId
            buildConfigField(
                "String",
                "ADMOB_REWARDED_AD_UNIT_ID",
                "\"${secretsProps.getProperty("ADMOB_REWARDED_AD_UNIT_ID") ?: admobTestRewardedUnitId}\""
            )

            for ((name, value) in firebaseResValues("com.skeler.scanely")) {
                resValue("string", name, value)
            }
        }
        // Performance measurement only (frame stats): release code (R8, not debuggable) installed over
        // the debug app with the debug key, so its sign-in and data stay. Never shipped.
        create("benchmark") {
            initWith(getByName("release"))
            isDebuggable = false
            isProfileable = true
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            manifestPlaceholders["admobAppId"] = admobTestAppId
            buildConfigField("String", "ADMOB_REWARDED_AD_UNIT_ID", "\"$admobTestRewardedUnitId\"")
            for ((name, value) in firebaseResValues("com.skeler.scanely.debug")) {
                resValue("string", name, value)
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    packaging {
        resources {
            // bcprov, bcpkix and bcutil each ship an identical copy of the Bouncy Castle licence.
            // pickFirsts rather than excludes so one copy still ships with the app.
            pickFirsts += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
            // Picnic's LowMC constant tables (1.2 MB), read only when a Picnic engine is built.
            // CMS signing and verification use RSA/ECDSA through the classic provider; no
            // post-quantum algorithm is ever instantiated. Only resources go: pqc classes are dex.
            excludes += "org/bouncycastle/pqc/**"
        }
    }
}

// Exported so every change to the chat cache is a reviewed schema with a written migration.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    constraints {
        // play-services-ads drags in WorkManager 2.7.0, which predates the behaviour changes
        // this app's targetSdk assumes. Nothing here calls WorkManager; this only floors it.
        implementation(libs.androidx.work.runtime) {
            because("play-services-ads resolves androidx.work to 2.7.0")
        }
    }

    implementation(libs.androidx.core)
    // Follows a background design and posts "your PDF is ready" (DesignJobWorker).
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.exifinterface)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.material.color.utilities)

    implementation(libs.datastore.preferences)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    ksp(libs.hilt.android.compiler)

    implementation(libs.serialization.json)
    implementation(libs.navigation.compose)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.accompanist.permissions)
    implementation(libs.play.services.ads)
    implementation(libs.user.messaging.platform)
    implementation(libs.mlkit.barcode)
    implementation(libs.zxing.cpp)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.document.scanner)
    implementation(libs.onnxruntime.android)

    implementation(libs.pdfium.android)
    implementation(libs.bouncycastle.pkix)
    // pdfbox-android pins Bouncy Castle's jdk15to18 1.72 artifacts, which are the same
    // org.bouncycastle packages as the jdk18on 1.85 above and so duplicate every class at dex time.
    implementation(libs.pdfbox.android) {
        exclude(group = "org.bouncycastle")
    }

    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.retrofit.kotlinx.serialization)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    implementation(libs.profileinstaller)
    "baselineProfile"(project(":baselineprofile"))

    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
    // android.jar's org.json is a throwing stub; the real one lets us unit-test JSON export.
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.leakcanary)
}

// Scoped so the doFirst closure captures a String and not the build script, which the
// configuration cache cannot serialize. The check runs at execution time rather than in the
// release build type, because buildTypes is evaluated for every task including CI's debug ones.
run {
    val firebaseConfigPath = firebaseConfigFile.absolutePath
    val backendProblem = backendUrlProblem(scanlyApiBaseUrl())

    // preReleaseBuild, not assembleRelease: the lifecycle task's doFirst only runs after the whole
    // release has compiled, and this answer is known before anything does.
    tasks.matching { it.name == "preReleaseBuild" }.configureEach {
        doFirst {
            if (backendProblem != null) throw GradleException(backendProblem)
        }
    }
    val abis = releaseAbis.toList()
    val fullAbis = fullReleaseAbis.toList()

    tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
        doFirst {
            if (!File(firebaseConfigPath).exists()) {
                throw GradleException(
                    "app/google-services.json is missing. A release artifact built without it " +
                        "ships placeholder Firebase configuration and cannot sign anyone in."
                )
            }
        }
    }

    // A bundle missing an ABI is the failure that does not announce itself: it uploads, it
    // passes review, and the device classes it dropped simply never see the listing.
    tasks.matching { it.name == "bundleRelease" }.configureEach {
        doFirst {
            if (abis.toSet() != fullAbis.toSet()) {
                throw GradleException(
                    "bundleRelease was configured for ${abis.joinToString(", ")} instead of " +
                        "${fullAbis.joinToString(", ")}. Build the Play bundle without " +
                        "-PreleaseAbi, and check that gradle.properties does not set it."
                )
            }
        }
    }
}

baselineProfile {
    automaticGenerationDuringBuild = false
    dexLayoutOptimization = true
}
