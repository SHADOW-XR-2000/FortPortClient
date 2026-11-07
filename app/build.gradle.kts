plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Раскомментируйте после подключения Firebase (см. README)
    // id("com.google.gms.google-services")
}

android {
    namespace = "ru.fortport.client"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.fortport.client"
        minSdk = 26
        targetSdk = 34
        // CI передаёт номер сборки (-PversionCode=N): по нему приложение понимает, что вышло обновление
        versionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = "0.6.4"
    }

    // Подпись релизов. Ключ один и тот же для всех сборок — иначе Android не поставит
    // обновление поверх установленного приложения. Параметры приходят из секретов GitHub (release.yml).
    val releaseKeystore = System.getenv("FP_KEYSTORE_FILE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("FP_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FP_KEY_ALIAS")
                keyPassword = System.getenv("FP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Push-уведомления через Firebase Cloud Messaging.
    // Требует подключения проекта к Firebase и файла google-services.json (см. README).
    // implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    // implementation("com.google.firebase:firebase-messaging-ktx")
}
