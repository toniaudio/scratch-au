plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Versione dell'app: 100 + numero di commit (lo calcola il workflow). Deve solo crescere, altrimenti
// Android rifiuta l'aggiornamento; il numero dei run di GitHub ripartirebbe da 1 se il workflow cambiasse file.
val numeroBuild = (System.getenv("DVS_VERSIONE") ?: "1").toInt()

android {
    namespace = "com.toniaudio.djaaay"
    compileSdk = 35
    // Su GitHub Actions si usano NDK e CMake già installati nel runner (scelti dal workflow)
    System.getenv("DVS_NDK")?.let { ndkVersion = it }

    defaultConfig {
        applicationId = "com.toniaudio.dvs"
        minSdk = 28
        targetSdk = 35
        versionCode = numeroBuild
        versionName = "0.1.$numeroBuild"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-O2")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    // Chiave di firma fissa (secret GitHub), così ogni nuova versione si installa sopra la precedente
    val chiave = System.getenv("DVS_KEYSTORE_FILE")
    signingConfigs {
        if (chiave != null) {
            create("dvs") {
                storeFile = file(chiave)
                storePassword = System.getenv("DVS_KEYSTORE_PASSWORD")
                keyAlias = "dvs"
                keyPassword = System.getenv("DVS_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 toglie il codice inutilizzato: APK da ~20 MB a pochi MB
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (chiave != null) signingConfigs.getByName("dvs") else signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            System.getenv("DVS_CMAKE")?.let { version = it }
        }
    }

    buildFeatures {
        compose = true
        prefab = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.oboe:oboe:1.9.0")
}
