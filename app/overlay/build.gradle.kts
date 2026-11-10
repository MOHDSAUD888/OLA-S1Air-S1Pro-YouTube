plugins {
    id("com.android.application")
}

android {
    namespace = "dev.olatube"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.olatube"
        minSdk = 24
        // Dash is Android 10 (API 29). Staying at targetSdk 29 avoids the
        // Android 12+ foreground-service-type + runtime-notification plumbing
        // we would otherwise have to add for a device that never needs it.
        targetSdk = 29
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
