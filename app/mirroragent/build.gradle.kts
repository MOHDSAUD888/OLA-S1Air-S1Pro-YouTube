plugins {
    id("com.android.application")
}

/**
 * This module is never installed as an app. It is built only to get a
 * classes.dex we can run on the dash with:
 *   CLASSPATH=/data/local/tmp/mirror.apk app_process / dev.olatool.mirror.Agent ...
 * (exactly how scrcpy-server is loaded). Running under app_process, not as an
 * installed app, is what lets the hidden SurfaceControl / InputManager calls
 * through on Android 10.
 */
android {
    namespace = "dev.olatool.mirror"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.olatool.mirror"
        minSdk = 24
        targetSdk = 29
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
