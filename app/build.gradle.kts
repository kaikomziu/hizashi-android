plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// リリース用の署名情報は環境変数から受け取る（鍵そのものはリポジトリに入れない）。
// 環境変数が無いとき（普通のビルド）は、デバッグ用の鍵で署名する。
val releaseKeystore: String? = System.getenv("HIZASHI_KEYSTORE")

android {
    namespace = "io.github.kaikomziu.hizashi"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.kaikomziu.hizashi"
        minSdk = 29
        targetSdk = 34
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("HIZASHI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HIZASHI_KEY_ALIAS")
                keyPassword = System.getenv("HIZASHI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.webkit:webkit:1.11.0")
}
