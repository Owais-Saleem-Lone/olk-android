import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Resolves a build secret, in precedence order:
 *   1. an environment variable (how CI supplies them),
 *   2. `secrets.properties` in the repo root (git-ignored, your local values),
 *   3. `secrets.defaults.properties` (committed, placeholders only).
 *
 * The Supabase anon key is *not* a credential — it is published in the web app's
 * JavaScript bundle and every row it can reach is gated by RLS. It is kept out of
 * git anyway so that rotating the Supabase project does not mean rewriting history.
 */
fun secret(key: String): String {
    System.getenv(key)?.takeIf { it.isNotBlank() }?.let { return it }
    val local = rootProject.file("secrets.properties")
    val defaults = rootProject.file("secrets.defaults.properties")
    val props = Properties()
    if (defaults.exists()) defaults.inputStream().use(props::load)
    if (local.exists()) local.inputStream().use(props::load)
    return props.getProperty(key).orEmpty()
}

/**
 * The Play upload key. The keystore itself lives OUTSIDE the repo; its path and passwords
 * come through [secret] like everything else. Until all four are set, release builds stay
 * unsigned — which is what CI builds, since it has none of them.
 */
val uploadKeystore = secret("OLK_UPLOAD_KEYSTORE")
val uploadSigningReady = listOf(
    "OLK_UPLOAD_KEYSTORE",
    "OLK_UPLOAD_KEYSTORE_PASSWORD",
    "OLK_UPLOAD_KEY_ALIAS",
    "OLK_UPLOAD_KEY_PASSWORD",
).all { secret(it).isNotBlank() }

android {
    namespace = "com.openlibrarykashmir.olk"
    compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.compileSdkMinor.get().toInt()

    defaultConfig {
        applicationId = "com.openlibrarykashmir.olk"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", "\"${secret("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${secret("SUPABASE_ANON_KEY")}\"")
        // Only the team-application upload goes to the website itself.
        buildConfigField("String", "WEBSITE_URL", "\"${secret("WEBSITE_URL")}\"")
    }

    signingConfigs {
        if (uploadSigningReady) {
            create("upload") {
                storeFile = file(uploadKeystore)
                storePassword = secret("OLK_UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = secret("OLK_UPLOAD_KEY_ALIAS")
                keyPassword = secret("OLK_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (uploadSigningReady) signingConfig = signingConfigs.getByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
