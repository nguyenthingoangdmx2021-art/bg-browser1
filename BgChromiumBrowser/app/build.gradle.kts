plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.senior.bgbrowser"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.senior.bgbrowser"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0-full"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.media:media:1.7.0")
}
