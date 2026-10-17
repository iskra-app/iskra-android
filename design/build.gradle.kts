plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "moscow.swag.iskra.design"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    buildFeatures {
        compose = true
    }

    lint {
        error += listOf("MissingTranslation", "MissingQuantity", "ExtraTranslation", "HardcodedText")
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Никакого Matrix и никакого :core: проверяет `checkModuleRules`.
    implementation(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    // Цвет значков строки состояния под тему приложения (`WindowCompat`).
    implementation(libs.androidx.core)
    implementation(libs.kotlinx.collections.immutable)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.kotlin.test)
}
