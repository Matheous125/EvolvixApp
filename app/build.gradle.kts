plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp") version "2.3.2"
    kotlin("plugin.serialization") version "2.2.10"
}

android {
    namespace = "com.example.evolvix"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.evolvix"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Restrict native ABIs to arm64-v8a only.
        // The x86_64 build of libtensorflowlite_jni.so is not aligned to 16 KB page
        // boundaries (required by Android 15+ / Google Play from Nov 2025). Real Android
        // devices are exclusively ARM64. For emulator testing, create an arm64-v8a AVD
        // in Android Studio: Device Manager → Create → choose an arm64-v8a system image.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation ("com.google.android.material:material:1.12.0")
    implementation("androidx.navigation:navigation-compose:2.9.0")

    implementation("androidx.room:room-runtime:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    testImplementation("androidx.room:room-testing:2.7.1")

    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("com.itextpdf:itext7-core:7.2.5")

    // AndroidX emoji picker — displayed in a ModalBottomSheet in EditHabitScreen.
    // The selected emoji is stored as a plain Unicode String in HabitEntity.iconKey.
    implementation("androidx.emoji2:emoji2-emojipicker:1.5.0")

    // TensorFlow Lite — on-device ML runtime for Phase 6.5 (HabitSuccess /
    // HabitIcon / ReminderTemplate classifiers). Used exclusively by
    // [TfliteHabitPredictor]; the rest of the app never imports tflite types.
    // LiteRT 1.0.1 is the official rebrand of TensorFlow Lite by Google.
    // It is the first on-device ML runtime with LOAD segments aligned at 16 KB boundaries,
    // satisfying the Android 15+ (API 35) Play Store requirement from November 2025.
    // The Java/Kotlin Interpreter API is unchanged — only the Maven artifact changed.
    implementation("com.google.ai.edge.litert:litert:1.0.1")
}

// Prevent AGP from compressing .tflite assets — tflite loaders mmap the raw bytes
// directly out of the APK and cannot read them if they are compressed.
android {
    androidResources {
        noCompress.add("tflite")
    }
}