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

    // Relay в памяти, мост-заглушка и TLS-сервер — и JVM-тестам транспорта, и инструментальному
    // `BlindRouteSdkTest`, где через них входит и синхронизируется настоящий SDK.
    sourceSets {
        getByName("test").kotlin.directories.add("src/sharedTest/kotlin")
        getByName("androidTest").kotlin.directories.add("src/sharedTest/kotlin")
    }

    lint {
        warningsAsErrors = false
        error += listOf("MissingTranslation", "MissingQuantity", "ExtraTranslation")
    }
}

kotlin {
    jvmToolchain(21)
}

// Крейт клиента моста, собранный под эту машину (`:tunnel`, `generateTunnelBindings`): тесты
// транспорта гоняют настоящие окна и запечатывание, а не заглушку.
tasks.withType<Test>().configureEach {
    dependsOn(":tunnel:generateTunnelBindings")
    systemProperty("jna.library.path", rootProject.layout.projectDirectory.dir("tunnel/build/generated/rust/host").asFile.path)
}

dependencies {
    // Весь Matrix и вся его криптография — это SDK. Ни один его тип не выходит выше :core.
    implementation(libs.matrix.sdk)
    // Клиент моста: крейт через uniffi (docs/PROMPT.md, 9.3); relay'и и свои запросы — на OkHttp.
    implementation(project(":tunnel"))
    implementation(libs.okhttp)
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
    // Тесты транспорта зовут настоящий Rust: нужен JNA с нативной частью под эту машину.
    testImplementation(libs.jna)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)

    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.okhttp.tls)
}
