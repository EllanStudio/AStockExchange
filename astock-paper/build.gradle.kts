plugins {
    java
}

// Build against the original Paper 26.2 floor or the current 26.3 API.
val paperApiVersion = providers.gradleProperty("paperApiVersion")
    .orElse("26.3.build.157-beta")
    .get()

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    // Only plugin.yml has a placeholder; config.yml contains no expansion tokens.
    expand("paperApiMajor" to paperApiVersion.substringBefore(".build"))
}

tasks.jar {
    archiveFileName.set("AStockPaper-${project.version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
