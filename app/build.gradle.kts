plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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

    buildTypes {
        debug {
            // Стоит рядом с релизом на одном телефоне.
            applicationIdSuffix = ".dev"
        }
        release {
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

    testImplementation(libs.kotlin.test)
}
