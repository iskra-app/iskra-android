plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// FCM (gms) включается файлом проекта Firebase: он не секрет и может лежать в репозитории, но только в
// app/src/gms/ (docs/PROMPT.md, 10.2). Нет файла — gms собирается без пушей и так и говорит в настройках.
val firebaseConfigured = file("src/gms/google-services.json").exists()
if (firebaseConfigured) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
}

android {
    namespace = "wtf.iskra"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // Тот же, что bundle ID на iOS. `app_id` pusher'а — другая строка
        // (`wtf.iskra-android`, PushRegistration.APP_ID): в applicationId дефис запрещён.
        applicationId = "wtf.iskra"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // Синхронно с iOS (MARKETING_VERSION в Apps/project.yml). versionCode растёт монотонно и
        // одинаков у обоих flavor'ов и всех ABI одной версии.
        versionCode = 1
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Семь языков, и только они (docs/PROMPT.md, 7). Без фильтра в APK едут переводы всех
    // библиотек; с неправильным — выкидываются наши, а тесты на JVM продолжают проходить.
    androidResources {
        localeFilters += listOf("en", "ru", "de", "es", "fr", "ja", "b+zh+Hans")
        generateLocaleConfig = false
    }

    // Ключ релиза — только в секретах CI и у автора (docs/PROMPT.md, 19): потерян — ни один установленный
    // APK больше не обновится поверх. Без переменных сборка неподписанная, как у любого клона.
    val keystore = providers.environmentVariable("ISKRA_KEYSTORE_FILE").orNull
    if (keystore != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("ISKRA_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ISKRA_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ISKRA_KEY_PASSWORD").get()
                // v3 даёт ротацию ключа без потери обновлений. v1 (JAR) при minSdk 29 не нужен, а v2
                // apksigner при нём и не ставит: каждый поддерживаемый Android проверяет v3.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // Стоит рядом с релизом на одном телефоне.
            applicationIdSuffix = ".dev"
            // Debug обновлять нечем: в релизах лежат подписанные релизным ключом APK (docs/PROMPT.md, 19.1).
            buildConfigField("boolean", "UPDATE_CHECK", "false")
        }
        release {
            // Встроенная проверка обновлений. Сборка для магазина, который обновляет сам, выключит её здесь.
            buildConfigField("boolean", "UPDATE_CHECK", "true")
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Как приходят пуши (раздел 10.2). Оба flavor'а в каждом релизе.
    flavorDimensions += "push"
    productFlavors {
        create("gms") {
            dimension = "push"
        }
        create("foss") {
            dimension = "push"
        }
    }

    // `.so` SDK — десятки мегабайт на ABI: сплиты обязательны, universal APK не публикуется.
    // armeabi-v7a не собирается: 32-битных телефонов на Android 10+ почти нет (раздел 19).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        // Несжатые выровненные `.so`, без распаковки на диск.
        jniLibs.useLegacyPackaging = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        error += listOf("MissingTranslation", "MissingQuantity", "ExtraTranslation", "HardcodedText")
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":design"))
    implementation(project(":features"))

    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)

    // Только gms: foss собирается без единой библиотеки Google (checkModuleRules).
    "gmsImplementation"(libs.firebase.messaging)

    testImplementation(libs.kotlin.test)
}

if (firebaseConfigured) {
    // У foss файла нет и быть не должно: плагин смотрит во все варианты, и молчать ему о foss — правильно.
    extensions.configure<com.google.gms.googleservices.GoogleServicesPlugin.GoogleServicesPluginConfig> {
        missingGoogleServicesStrategy = com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy.IGNORE
    }
}
