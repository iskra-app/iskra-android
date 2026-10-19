plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "wtf.iskra.core"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    lint {
        warningsAsErrors = false
        error += listOf("MissingTranslation", "MissingQuantity", "ExtraTranslation")
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Весь Matrix и вся его криптография — это SDK. Ни один его тип не выходит выше :core.
    implementation(libs.matrix.sdk)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.exifinterface)
    // Подготовка видео к отправке: сжатие и чистый контейнер без метаданных (Apache-2.0).
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
