plugins {
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("dev.olatool.cli.Main")
}

// `gradle :cli:run --args="192.168.1.5 probe"` from the app directory.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
