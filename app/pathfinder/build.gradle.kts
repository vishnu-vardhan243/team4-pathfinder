plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.team4.pathfinder"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.team4.pathfinder"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        // Real phones are arm64; x86_64 keeps the emulator working.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // Skill graphs ship straight from the repo's content/ folder: one copy of the data for the app, website and validator.
    sourceSets { getByName("main") { assets.srcDirs("../../content") } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)

    // On-device Llama: same runtime version as the v1.5.1 exporter that built the .pte.
    implementation("org.pytorch:executorch-android:1.5.1")
    implementation("com.facebook.fbjni:fbjni:0.5.1")

    testImplementation(libs.junit)
    // Android's org.json is a stub in JVM unit tests; use the real library there.
    testImplementation("org.json:json:20250107")
}
