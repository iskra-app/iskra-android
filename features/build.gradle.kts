plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "wtf.iskra.features"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    buildFeatures {
        compose = true
    }

    // Скриншот-тесты (`ScreenshotTest`) рисуют настоящие ресурсы — строки, значки — под Robolectric.
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
    // Единственное место, где модель и дизайн встречаются.
    implementation(project(":core"))
    implementation(project(":design"))
    implementation(libs.androidx.core)
    implementation(libs.aboutlibraries.core)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    // Значки Material (на iOS — SF Symbols). Неиспользуемые выбрасывает R8.
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.activity.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui.compose)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)
    // Чтение QR: только декодер, без сервисов Google (docs/PROMPT.md, 5 — «не ML Kit»).
    implementation(libs.zxing.core)
    // Блокировка приложения: BiometricPrompt, откат на пин устройства. `api`: окну нужен `FragmentActivity`, а он приходит отсюда.
    api(libs.androidx.biometric)
    api(libs.androidx.fragment)
    implementation(libs.maplibre)
    // Тайлы карты — тем же маршрутом, что всё остальное: клиент MapLibre от `AuthenticationService.outsideHttp`.
    implementation(libs.okhttp)
    implementation(libs.androidx.glance)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.collections.immutable)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
}

// Эталоны — как в `:design`: `recordRoborazziDebug` записывает, `verifyRoborazziDebug` сверяет.
roborazzi {
    outputDir.set(file("src/test/screenshots"))
}
