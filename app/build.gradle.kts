import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Datos de firma: se leen de keystore.properties (en la raíz, fuera de git).
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasKeystore = keystoreProps.containsKey("storeFile")

android {
    namespace = "com.example.gamepadhid"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.gamepadhid"
        minSdk = 28          // BluetoothHidDevice exige API 28+
        targetSdk = 36       // Google Play exige API 36 desde el 31/08/2026
        versionCode = 3
        versionName = "2.0.0"
    }

    signingConfigs {
        create("release") {
            if (hasKeystore) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        aidl = true // IGameCastService (servicio de usuario de Shizuku)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Shizuku (RikkaApps). 13.1.5 existe en Maven Central y es la última publicada (sep. 2023).
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // Solo para el respaldo con ShizukuBinderWrapper (APIs ocultas desde el proceso de la app).
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
