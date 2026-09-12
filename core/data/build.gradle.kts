plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The data layer: Supabase client, DTOs and repositories.
 *
 * Deliberate constraint — nothing in this module may import `android.*` or
 * `androidx.compose.*`. Everything here is plain Kotlin talking to Postgres.
 * That is what makes a future iOS app (Compose Multiplatform + supabase-kt, both
 * already KMP-native) a matter of moving this module to `commonMain` rather than
 * a rewrite. `./gradlew :core:data:checkNoAndroidImports` enforces it.
 */
android {
    namespace = "com.openlibrarykashmir.olk.core.data"
    compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.compileSdkMinor.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(platform(libs.supabase.bom))
    api(libs.supabase.auth)
    api(libs.supabase.postgrest)
    api(libs.supabase.realtime)
    api(libs.supabase.storage)
    implementation(libs.ktor.client.okhttp)

    implementation(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    // koin-core, not koin-android: this module must not depend on the Android
    // framework, per the constraint enforced by checkNoAndroidImports below.
    api(platform(libs.koin.bom))
    api(libs.koin.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}

/**
 * Guards the KMP-readiness promise above. Fails the build if Android or Compose
 * types leak into the data layer, which is exactly the drift that turns a future
 * iOS port from "move a folder" into "rewrite the app".
 */
val checkNoAndroidImports = tasks.register("checkNoAndroidImports") {
    group = "verification"
    description = "Fails if :core:data imports android.* or androidx.compose.*"

    val sourceDir = layout.projectDirectory.dir("src/main/kotlin")
    inputs.dir(sourceDir)
    // No meaningful file output; declare one so the task is cacheable and
    // does not invalidate the configuration cache.
    val marker = layout.buildDirectory.file("reports/no-android-imports.txt")
    outputs.file(marker)

    doLast {
        val offenders = sourceDir.asFile.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines()
                    .withIndex()
                    .filter { (_, line) ->
                        val t = line.trimStart()
                        t.startsWith("import android.") || t.startsWith("import androidx.compose.")
                    }
                    .map { (i, line) -> "${file.name}:${i + 1}: ${line.trim()}" }
            }
            .toList()

        marker.get().asFile.apply {
            parentFile.mkdirs()
            writeText(if (offenders.isEmpty()) "clean\n" else offenders.joinToString("\n"))
        }

        if (offenders.isNotEmpty()) {
            throw GradleException(
                ":core:data must stay platform-agnostic, but found Android/Compose imports:\n" +
                    offenders.joinToString("\n") { "  $it" },
            )
        }
    }
}

tasks.named("check") { dependsOn(checkNoAndroidImports) }
