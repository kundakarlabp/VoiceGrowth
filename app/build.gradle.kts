plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
}

val releaseKeystorePath = System.getenv("VOICEGROWTH_KEYSTORE_PATH")
val releaseKeystorePassword = System.getenv("VOICEGROWTH_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("VOICEGROWTH_KEY_ALIAS")
val releaseKeyPassword = System.getenv("VOICEGROWTH_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.voicegrowth.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.voicegrowth.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "2.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Side-by-side preview; never overwrite an existing signed clinical recording app.
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
        }
        // Separate APK; no changes to production or the opt-in Whisper preview.
        create("gemmaBenchmark") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".gemma"
            versionNameSuffix = "-gemma-benchmark"
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1,DEPENDENCIES,INDEX.LIST}" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    // Only in the separate benchmark APK. 0.16.1 supports the existing Kotlin 2.2 compiler.
    add("gemmaBenchmarkImplementation", "com.google.ai.edge.litertlm:litertlm-android:0.16.1")
    // Pilot only: local file ASR, no Gemini/OpenAI API or background server.
    implementation("dev.ffmpegkit-maintained:whisper-android:1.0.0")
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.ui.tooling)
}
