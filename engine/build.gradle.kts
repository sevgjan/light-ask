// Sideload-only device features for Ask: colour on a grayscale phone, saving pictures to the gallery, the
// camera, and opening links in the browser. They need an Android Context, which a Light SDK tool may not
// use, so they live outside the tool module (like LightsApp's engine). tools/dev.sh syncs this folder into
// the shared SDK copy, and its settings.gradle.kts includes it when present.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.sevgjan.ask.engine"
    compileSdk = rootProject.ext["compileSdk"] as Int
    defaultConfig {
        minSdk = rootProject.ext["minSdk"] as Int
    }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
        targetCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(rootProject.ext["jvmTarget"] as String))
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
}
