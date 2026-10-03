@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlinx.serialization)
}

val libsNdk = libs.versions.android.ndk.version.get()
val libsCmake = libs.versions.cmake.version.get()
val libsCompileSdk = libs.versions.android.compileSdk.get().toInt()
val libsMinSdk = libs.versions.android.minSdk.get().toInt()
val libsBuildTools = libs.versions.android.buildTools.version.get()

android {
    namespace = "libs.libs.libs"
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
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    defaultConfig {
        minSdk = libsMinSdk

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

    buildTypes {
        release {
            isMinifyEnabled = false
            vcsInfo.include = false
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        aidl = true
        prefab = true
    }

    lint {
        checkDependencies = false
        // abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

dependencies {
    coreLibraryDesugaring(libs.android.jdk.libs)
    implementation(libs.bundles.kotlinx.android)
    implementation(libs.lsposed.hiddenapibypass)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.annotation.experimental)
    implementation(libs.bcprov.jdk18on)
    implementation(libs.bcpkix.jdk18on)
}
