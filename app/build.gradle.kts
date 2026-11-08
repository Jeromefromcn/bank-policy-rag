plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":core"))
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.2"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-elasticsearch")
    // Boot 4.1.2 manages 9.4.8; match the 9.5.x server.
    implementation("co.elastic.clients:elasticsearch-java:9.5.6")
    implementation("org.springframework.ai:spring-ai-starter-model-deepseek")
    implementation("org.springframework.ai:spring-ai-starter-model-ollama")
    implementation("com.github.f4b6a3:ulid-creator:5.2.4")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers-elasticsearch")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootDir
    jvmArgs(providers.gradleProperty("appJvmArgs").getOrElse("-Xmx1g").split(" "))
}

springBoot {
    mainClass.set("rag.app.RagApplication")
}
