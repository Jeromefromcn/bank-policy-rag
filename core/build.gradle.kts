plugins {
    `java-test-fixtures`
}

// Plain Java: no Spring and no Elasticsearch dependency (spec 00 §3.1).
dependencies {
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation("com.knuddels:jtokkit:1.1.0")
    implementation("org.yaml:snakeyaml")

    // Self-made corpus builders shared with app tests (generated PDF, no SPM text).
    testFixturesImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    testFixturesImplementation("org.apache.pdfbox:pdfbox:3.0.8")
}
