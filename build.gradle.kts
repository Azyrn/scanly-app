// Declared (not applied) at the root so Android Studio can resolve the AGP version from the
// root project model — without this, sync leaves the IDE unable to determine AGP and it cancels
// Assemble before Gradle runs. The modules apply what they need.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.baselineprofile) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
