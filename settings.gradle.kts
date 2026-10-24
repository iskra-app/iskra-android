pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "iskra-android"

// Правило зависимостей (docs/PROMPT.md, 3) проверяет задача `checkModuleRules` в корневом
// build.gradle.kts:
//   :core     — SDK, никакого Compose;
//   :design   — Compose, никакого Matrix и никакого :core;
//   :features — экраны, единственное место, где они встречаются;
//   :app      — тонкая оболочка без логики;
//   :tunnel   — uniffi-обёртка крейта клиента моста, только крейт (docs/PROMPT.md, 9.3).
include(":core", ":design", ":features", ":app", ":tunnel")
