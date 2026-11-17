plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.randomphotowallpaper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.randomphotowallpaper"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
