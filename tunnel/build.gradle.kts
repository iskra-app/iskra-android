import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
}

/**
 * `:tunnel` — uniffi-обёртка крейта клиента моста (docs/PROMPT.md, 9.3). Крейт лежит здесь же
 * (`Cargo.toml`, `rust/`), Kotlin из него генерируется, `.so` собирает `cargo-ndk`. Своего Kotlin в
 * модуле нет: ввод-вывод туннеля — в `:core/transport`.
 *
 * Три сборки одного крейта:
 * - под телефон и эмулятор (`arm64-v8a`, `x86_64`) — в `jniLibs`;
 * - под эту машину — для JVM-тестов `:tunnel` и `:core`, которые зовут настоящий Rust, а не заглушку,
 *   и для генератора биндингов, который читает метаданные uniffi из собранной библиотеки;
 * - `cargo test` — в `check`, вместе с тестами вендоренного `iskra-tunnel-proto`.
 *
 * Профиль всегда release: доказательство работы для `open` в debug-сборке Rust — секунды на
 * каждое соединение, и отлаживать на ней туннель значило бы отлаживать не тот туннель.
 */

android {
    namespace = "wtf.iskra.tunnel"
    compileSdk = libs.versions.compileSdk.get().toInt()
    // r28+ выравнивает .so на 16 KB по умолчанию: телефоны с 16 KB страницами иначе их не загрузят.
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Биндинги uniffi ходят в .so через JNA — ту же, что у SDK.
    api(variantOf(libs.jna) { artifactType("aar") })

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.serialization.json)
    // На JVM нужен JNA с нативной частью под эту машину; в aar — только Android.
    testImplementation(libs.jna)
}

/** cargo из PATH демона Gradle или из ~/.cargo/bin: демон, запущенный из IDE, PATH оболочки не видит. */
val cargoHomeDir = providers.environmentVariable("CARGO_HOME").orElse(providers.systemProperty("user.home").map { "$it/.cargo" })
val crate = layout.projectDirectory
val cargoTarget = crate.dir("target")

/** Исходники крейта и proto: от них зависит каждая сборка Rust. */
fun Task.rustInputs() {
    inputs.files(crate.file("Cargo.toml"), crate.file("Cargo.lock"), crate.file("uniffi.toml"))
    inputs.dir(crate.dir("rust"))
    inputs.dir(rootProject.layout.projectDirectory.dir("third_party/iskra-tunnel-proto"))
}

abstract class CargoTask : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations

    @get:Internal abstract val crateDir: DirectoryProperty

    @get:Internal abstract val targetDir: DirectoryProperty

    @get:Input abstract val cargoHome: Property<String>

    fun cargo(vararg arguments: String, environment: Map<String, String> = emptyMap()) {
        val bin = "${cargoHome.get()}/bin"
        exec.exec {
            workingDir = crateDir.get().asFile
            executable = File(bin, "cargo").takeIf { it.canExecute() }?.path ?: "cargo"
            args(*arguments)
            environment("PATH", "$bin${File.pathSeparator}${System.getenv("PATH").orEmpty()}")
            environment("CARGO_TARGET_DIR", targetDir.get().asFile.path)
            environment.forEach { (key, value) -> environment(key, value) }
        }
    }
}

/** `.so` под телефон и эмулятор, разложенные по ABI, — каталог для `jniLibs`. */
abstract class CargoNdkTask : CargoTask() {
    @get:Internal abstract val ndkDir: DirectoryProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        cargo(
            "ndk", "--platform", "29", "-t", "arm64-v8a", "-t", "x86_64", "-o", out.path,
            "build", "--release", "--lib",
            environment = mapOf("ANDROID_NDK_HOME" to ndkDir.get().asFile.path),
        )
    }
}

/** Библиотека под эту машину и Kotlin-биндинги, сгенерированные из неё. */
abstract class CargoBindingsTask : CargoTask() {
    @get:OutputDirectory abstract val hostLibDir: DirectoryProperty

    @get:OutputDirectory abstract val kotlinDir: DirectoryProperty

    @TaskAction
    fun build() {
        // Профиль bindings (Cargo.toml): release без strip, иначе на Linux генератору нечего читать.
        cargo("build", "--profile", "bindings", "--lib")
        val name = System.mapLibraryName("iskra_tunnel_client")
        val built = targetDir.get().file("bindings/$name").asFile
        val host = hostLibDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        built.copyTo(File(host, name), overwrite = true)

        val kotlin = kotlinDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        cargo(
            "run", "--profile", "bindings", "--quiet", "--features", "bindgen", "--bin", "uniffi-bindgen", "--",
            "generate", "--library", built.path, "--language", "kotlin", "--no-format", "--out-dir", kotlin.path,
        )
    }
}

abstract class CargoTestTask : CargoTask() {
    @TaskAction
    fun test() {
        cargo("test", "--release")
        // Тот же вопрос к вендоренной копии протокола: синхронизация, которая её обновила, проверяется
        // здесь раньше, чем что-нибудь собрано в APK (docs/PROMPT.md, 9.3).
        cargo("test", "--release", "-p", "iskra-tunnel-proto")
    }
}

val ndkLibs = tasks.register<CargoNdkTask>("cargoNdkBuild") {
    description = "Собирает крейт клиента моста под arm64-v8a и x86_64."
    crateDir.set(crate)
    targetDir.set(cargoTarget)
    cargoHome.set(cargoHomeDir)
    ndkDir.set(androidComponents.sdkComponents.ndkDirectory)
    outputDir.set(layout.buildDirectory.dir("generated/rust/jniLibs"))
    rustInputs()
}

val bindings = tasks.register<CargoBindingsTask>("generateTunnelBindings") {
    description = "Собирает крейт под эту машину и генерирует из него Kotlin-биндинги uniffi."
    crateDir.set(crate)
    targetDir.set(cargoTarget)
    cargoHome.set(cargoHomeDir)
    hostLibDir.set(layout.buildDirectory.dir("generated/rust/host"))
    kotlinDir.set(layout.buildDirectory.dir("generated/rust/kotlin"))
    rustInputs()
}

val cargoTest = tasks.register<CargoTestTask>("cargoTest") {
    group = "verification"
    description = "cargo test крейта клиента моста и вендоренного iskra-tunnel-proto."
    crateDir.set(crate)
    targetDir.set(cargoTarget)
    cargoHome.set(cargoHomeDir)
    rustInputs()
    outputs.upToDateWhen { false }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(ndkLibs, CargoNdkTask::outputDir)
        variant.sources.kotlin?.addGeneratedSourceDirectory(bindings, CargoBindingsTask::kotlinDir)
    }
}

/** Библиотека под эту машину — для JVM-тестов любого модуля, который зовёт Rust (`:core` тоже). */
val hostLibrary: Provider<Directory> = bindings.flatMap { it.hostLibDir }

tasks.withType<Test>().configureEach {
    dependsOn(bindings)
    systemProperty("jna.library.path", hostLibrary.get().asFile.path)
}

tasks.named("check") { dependsOn(cargoTest) }

extra["tunnelHostLibrary"] = hostLibrary
extra["tunnelBindings"] = bindings
