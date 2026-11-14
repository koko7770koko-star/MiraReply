plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.mirareply"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.mirareply"
        minSdk = 31          // Android 12 以上
        targetSdk = 36
        versionCode = 5
        versionName = "1.3.1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // どこでビルドしても同じ署名になるよう、固定の鍵を使う（上書きインストールできる）
    signingConfigs {
        create("fixed") {
            storeFile = file("mirareply.jks")
            storePassword = "mirareply"
            keyAlias = "mirareply"
            keyPassword = "mirareply"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // LiteRT-LM は新しい Kotlin でビルドされているため（Google AI Edge Gallery と同じ対処）
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // 端末内でLLMを動かすGoogle公式ランタイム（Google AI Edge Gallery と同じ版）
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
}
