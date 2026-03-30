plugins {
    id("com.gradleup.shadow") version "8.3.5"
    id("qupath-conventions")
}

qupathExtension {
    name = "FlowPath - qUMAP"
    group = "io.github.qupath"
    version = "0.6.3"
    description = "UMAP dimensionality reduction and visualization for multiplexed imaging data."
    automaticModule = "qupath.ext.qumap"
}

dependencies {
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)

    // SMILE UMAP - bundled into fat JAR (exclude native BLAS binaries to reduce size)
    implementation("com.github.haifengl:smile-core:4.3.0") {
        exclude(group = "org.bytedeco")
        exclude(group = "com.epam")
        exclude(group = "org.apache.commons", module = "commons-csv")
    }

    testImplementation(libs.bundles.qupath)
    testImplementation(libs.junit)
    testImplementation("org.openjfx:javafx-base:25.0.2")
    testImplementation("org.openjfx:javafx-graphics:25.0.2")
    testImplementation("org.openjfx:javafx-controls:25.0.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// Use dots instead of spaces in JAR filename to avoid illegal URI characters
tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    archiveBaseName.set("FlowPath.-.qUMAP")
}

tasks.withType<Jar> {
    archiveBaseName.set("FlowPath.-.qUMAP")
}
