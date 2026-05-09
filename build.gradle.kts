plugins {
    application
    id("java")
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation(libs.guava)
    implementation("com.formdev:flatlaf:3.5.1")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.3.0.202506031305-r")
}

application {
    mainClass = "TBuild"
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.named<Jar>("jar") {
    archiveFileName.set("TBuild.jar")
    manifest {
        attributes["Main-Class"] = "TBuild"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF")
    exclude("META-INF/*.RSA")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.EC")
}