plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation("tools.jackson.core:jackson-databind")
    implementation("org.yaml:snakeyaml")
    testImplementation(testFixtures(project(":core")))
}

application {
    mainClass.set("rag.eval.EvalRunner")
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
