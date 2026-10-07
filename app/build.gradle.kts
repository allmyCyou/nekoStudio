@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.text.SimpleDateFormat
import java.util.Date
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinx.serialization)
}

val libsCompileSdk = libs.versions.android.compileSdk.get().toInt()
val libsMinSdk = libs.versions.android.minSdk.get().toInt()
val libsTargetSdk = libs.versions.android.targetSdk.get().toInt()
val libsVersionCode = libs.versions.android.versionCode.get().toInt()

val buildDate = SimpleDateFormat("yyyyMMdd").format(Date())
val libsVersionPrefix = libs.versions.android.versionPrefix.get()

val libsNdk = libs.versions.android.ndk.version.get()
val libsCmake = libs.versions.cmake.version.get()
val libsBuildTools = libs.versions.android.buildTools.version.get()

val envNewStorePassword = providers.environmentVariable("RELEASE_STORE_PASSWORD").orElse("").get()
val envNewKeyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orElse("").get()
val envNewKeyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orElse("").get()

android {
    namespace = "com.adb.kitty"
    compileSdk = libsCompileSdk
    buildToolsVersion = libsBuildTools
    ndkVersion = libsNdk

    packaging {
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            merges += "kotlin-tooling-metadata.json"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    androidResources {
        generateLocaleConfig = true
    }

    defaultConfig {
        applicationId = "com.adb.kitty"
        minSdk = libsMinSdk
        targetSdk = libsTargetSdk
        versionCode = libsVersionCode
        versionName = "$libsVersionPrefix-$buildDate"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables { 
            useSupportLibrary = true
        }
        ndk {
            abiFilters.addAll(setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64", "riscv64"))
        }
        externalNativeBuild {
            cmake {
                abiFilters("arm64-v8a", "armeabi-v7a", "x86", "x86_64", "riscv64")
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = libsCmake
        }
    }

    bundle {
        language {
            enableSplit = true
        }
        density {
            enableSplit = true
        }
        abi {
            enableSplit = true
        }
    }

    signingConfigs {
        create("adb") {
        // keystore file，.bks & .jks & .p12
            storeFile = file("bash/new_key.jks")
            storePassword = envNewStorePassword
            keyAlias = envNewKeyAlias
            keyPassword = envNewKeyPassword
            storeType = "PKCS12"
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo.include = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), 
                "proguard-rules.pro"
            )
            optimization.keepRules {
                // ignoreFrom 只允许忽略来自远程库的依赖
                ignoreFrom("org.jetbrains.kotlinx:kotlinx-coroutines-android")
                ignoreFrom("org.lsposed.hiddenapibypass:hiddenapibypass")
                ignoreFrom("com.github.topjohnwu.libsu:core")
            }
            signingConfig = signingConfigs.getByName("adb")
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            signingConfig = signingConfigs.getByName("adb")
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
        prefab = true
    }

    lint {
        checkDependencies = false
      //  abortOnError = false
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

dependencies {
    implementation(projects.libs)
    coreLibraryDesugaring(libs.android.jdk.libs)
    runtimeOnly(libs.bundles.kotlinx.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.collection)
    implementation(libs.androidx.window)
    implementation(libs.lsposed.hiddenapibypass)
    implementation(libs.nayuki.qrcode)
    implementation(libs.zxing.core)
    implementation(libs.androidx.annotation.experimental)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.bundles.compose.lifecycle)
    debugImplementation(libs.bundles.compose.debug)
    implementation(libs.bundles.libsu)
}
