plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val youtubedlAndroid = "0.18.1" // kalau gagal resolve, coba "0.17.4"

// Versi mengikuti Semantic Versioning (MAJOR.MINOR.PATCH).
// Di CI, APP_VERSION diisi dari tag git (v1.2.3 -> 1.2.3). Lokal/non-tag: 1.0.0-dev.
val appVersionName: String = System.getenv("APP_VERSION")?.takeIf { it.isNotBlank() } ?: "1.0.0-dev"
val semverParts: List<Int> = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)").find(appVersionName)
    ?.groupValues?.drop(1)?.map { it.toInt() } ?: listOf(1, 0, 0)
// versionCode = MAJOR*10000 + MINOR*100 + PATCH  (1.2.3 -> 10203)
val appVersionCode: Int = semverParts[0] * 10000 + semverParts[1] * 100 + semverParts[2]

android {
    namespace = "com.example.ytdlpapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.ytdlpapp"
        minSdk = 29
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    // Hanya arm64-v8a (Android ARM 64-bit)
    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Keystore tetap (dari env/GitHub Secrets) supaya update bisa menimpa tanpa uninstall
    val ksFile = System.getenv("KEYSTORE_FILE")?.let { file(it) }
    val hasKeystore = ksFile != null && ksFile.exists()
    if (hasKeystore) {
        signingConfigs {
            create("shared") {
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    // Wajib: binary python/yt-dlp di-extract ke disk
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        debug {
            if (hasKeystore) signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            if (hasKeystore) signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")

    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlAndroid")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubedlAndroid")
}
