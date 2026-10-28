import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.licensee) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.aboutlibraries) apply false
}

/**
 * Правило зависимостей между модулями, проверенное сборкой, а не честным словом.
 *
 * В iOS всё устройство проекта — это то, что `IskraCore` никогда не видит SwiftUI, а `IskraDesign`
 * никогда не видит Matrix (docs/PROMPT.md, 3). Здесь то же правило, и нарушается оно не строчкой
 * `implementation(project(":core"))`, которую заметят на ревью, а транзитивно: библиотека, которая
 * притащила за собой Compose в `:core`. Поэтому проверяется *разрешённый* граф варианта, а не
 * объявленные зависимости.
 *
 * Тот же механизм держит `foss` без единой библиотеки Google (раздел 10.2): телефон без сервисов
 * Google — это и есть причина, по которой `foss` существует.
 */
abstract class CheckModuleRules : DefaultTask() {
    /** Префиксы запрещённого: `group:` для библиотек, `project :path` для модулей. */
    @get:Input
    abstract val forbidden: ListProperty<String>

    @get:Input
    abstract val components: ListProperty<String>

    @get:Input
    abstract val why: Property<String>

    @TaskAction
    fun check() {
        val found = components.get().filter { id -> forbidden.get().any { id.startsWith(it) } }.distinct().sorted()
        if (found.isNotEmpty()) {
            throw GradleException(
                "${why.get()}\nВ графе есть запрещённое:\n" + found.joinToString("\n") { "  $it" },
            )
        }
    }
}

fun ResolvedComponentResult.everything(): List<String> {
    val seen = linkedSetOf<String>()
    val queue = ArrayDeque(listOf(this))
    val visited = mutableSetOf<ResolvedComponentResult>()
    while (queue.isNotEmpty()) {
        val component = queue.removeFirst()
        if (!visited.add(component)) continue
        when (val id = component.id) {
            is ModuleComponentIdentifier -> seen += "${id.group}:${id.module}"
            is ProjectComponentIdentifier -> seen += "project ${id.projectPath}"
        }
        component.dependencies
            .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
            .forEach { queue += it.selected }
    }
    return seen.toList()
}

/** Правила: модуль → (варианты, запрещённое, почему). */
val moduleRules = mapOf(
    ":tunnel" to Triple(
        listOf("releaseCompileClasspath", "releaseRuntimeClasspath"),
        listOf("androidx.compose", "org.matrix.rustcomponents:", "project :core", "project :design", "project :features"),
        ":tunnel — только крейт клиента моста: ни Matrix, ни Compose, ни модулей приложения.",
    ),
    ":core" to Triple(
        listOf("releaseCompileClasspath", "releaseRuntimeClasspath"),
        listOf("androidx.compose"),
        ":core не зависит от Compose: модель не знает, как её нарисуют.",
    ),
    ":design" to Triple(
        listOf("releaseCompileClasspath", "releaseRuntimeClasspath"),
        listOf("org.matrix.rustcomponents:", "net.java.dev.jna:", "project :core"),
        ":design не знает ни Matrix, ни :core: пузырь рисует то, что ему дали.",
    ),
    ":app" to Triple(
        listOf("fossReleaseCompileClasspath", "fossReleaseRuntimeClasspath"),
        listOf("com.google.firebase:", "com.google.android.gms:", "com.google.android.play:", "com.google.mlkit:"),
        "foss собирается без единой библиотеки Google: это сборка для телефонов без них.",
    ),
)

subprojects {
    val rule = moduleRules[path] ?: return@subprojects
    val (configurationNames, forbiddenPrefixes, reason) = rule
    val task = tasks.register<CheckModuleRules>("checkModuleRules") {
        group = "verification"
        description = "Проверяет правило зависимостей модуля (docs/PROMPT.md, 3)."
        forbidden.set(forbiddenPrefixes)
        why.set(reason)
    }
    afterEvaluate {
        task.configure {
            configurationNames.forEach { name ->
                components.addAll(
                    configurations.named(name).flatMap { configuration ->
                        configuration.incoming.resolutionResult.rootComponent.map { it.everything() }
                    },
                )
            }
        }
        tasks.named("check") { dependsOn(task) }
    }
}
