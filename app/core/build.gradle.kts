plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    // okhttp is the Android-friendly transport; stub gives us ClientCalls so
    // no protoc-generated code is needed anywhere.
    implementation("io.grpc:grpc-okhttp:1.68.1")
    implementation("io.grpc:grpc-stub:1.68.1")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
