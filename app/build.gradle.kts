// Root of the Android build. The Gradle wrapper pins the version, so nothing
// here depends on what is installed on the machine.
plugins {
    id("com.android.application") version "8.7.3" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
