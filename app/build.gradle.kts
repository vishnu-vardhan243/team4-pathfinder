// Versions come from gradle/libs.versions.toml (same as Meta's LlamaDemo, so they are already in the Gradle cache).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
