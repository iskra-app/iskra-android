plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "wtf.iskra.design"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    buildFeatures {
        compose = true
    }

    // Скриншот-тесты рисуют настоящие ресурсы (шрифт, цвета) под Robolectric.
    testOptions.unitTests.isIncludeAndroidResources = true
    // Robolectric на JDK 21 трогает поля FileDescriptor (ApplicationSharedMemory SDK 36).
    testOptions.unitTests.all {
        it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
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
    // Схема Material 3 из акцента: тот же алгоритм, что у динамических цветов системы.
    implementation(libs.material.color.utilities)
    // Цвет значков строки состояния под тему приложения (`WindowCompat`).
    implementation(libs.androidx.core)
    implementation(libs.kotlinx.collections.immutable)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
}

// Эталоны — в репозитории, рядом с тестами: `recordRoborazziDebug` записывает, `verifyRoborazziDebug` сверяет.
roborazzi {
    outputDir.set(file("src/test/screenshots"))
}
