import java.net.URI
import java.security.MessageDigest

plugins {
    java
    application
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.javamodularity.moduleplugin") version "1.8.15"
    id("org.openjfx.javafxplugin") version "0.0.13"
    id("org.beryx.jlink") version "3.2.1"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

group = "org.server"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val junitVersion = "5.12.1"

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

application {
    mainModule.set("org.server.anonymous")
    mainClass.set("org.server.anonymous.Launcher")
}
kotlin {
    jvmToolchain(21)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

javafx {
    version = "21.0.6"
    modules = listOf("javafx.controls", "javafx.fxml")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("config/detekt/detekt.yml")
}

tasks.check {
    dependsOn("ktlintCheck")
    dependsOn("detekt")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
    // Aligns junit-platform-launcher with the engine version (Gradle's embedded launcher is older).
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.register("installGitHooks") {
    doLast {
        val hook =
            layout.projectDirectory
                .file("hooks/pre-commit")
                .asFile
        val target =
            layout.projectDirectory
                .dir(".git/hooks")
                .file("pre-commit")
                .asFile
        target.parentFile.mkdirs()
        hook.copyTo(target, overwrite = true)
        target.setExecutable(true)
        println("Installed git pre-commit hook -> $target")
    }
}

// --- Bundled Tor (expert bundle, SHA-256 pinned; see guide/dev/tor-bundling.md) ---
val torVersion = "15.0.19"

data class TorArtifact(
    val url: String,
    val sha256: String,
)

val torArtifacts =
    mapOf(
        "linux-x86_64" to
            TorArtifact(
                "https://dist.torproject.org/torbrowser/$torVersion/tor-expert-bundle-linux-x86_64-$torVersion.tar.gz",
                "5a8f19f5f119b5fa2a8fd799a3a532e3236ad36164241800d6302e32f0e1c2a9",
            ),
        "windows-x86_64" to
            TorArtifact(
                "https://dist.torproject.org/torbrowser/$torVersion/tor-expert-bundle-windows-x86_64-$torVersion.tar.gz",
                "6ac067402c7b4a3dc37887ed3754b3914b67fdc220c966190683e9ccf91abf0f",
            ),
    )

fun currentTorPlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val osKey =
        when {
            os.contains("win") -> "windows"
            os.contains("mac") || os.contains("darwin") -> "macos"
            else -> "linux"
        }
    val archKey = if (arch.contains("aarch64") || arch.contains("arm64")) "aarch64" else "x86_64"
    return "$osKey-$archKey"
}

val downloadTor by tasks.registering {
    val platform = currentTorPlatform()
    val artifact =
        torArtifacts[platform]
            ?: throw GradleException("No pinned Tor bundle for platform '$platform' (add one in torArtifacts)")
    val zip = layout.buildDirectory.file("tor/$platform/bundle.tar.gz")
    val outputDir = layout.projectDirectory.dir("src/main/resources/tor/$platform")
    inputs.property("url", artifact.url)
    inputs.property("sha256", artifact.sha256)
    outputs.dir(outputDir)
    doLast {
        val file = zip.get().asFile
        file.parentFile.mkdirs()
        println("Downloading Tor bundle ($platform)…")
        file.outputStream().use { out -> URI(artifact.url).toURL().openStream().use { it.copyTo(out) } }
        val actual =
            MessageDigest
                .getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
        if (actual != artifact.sha256) {
            throw GradleException("Tor bundle SHA-256 mismatch for $platform: expected ${artifact.sha256}, got $actual")
        }
        project.copy {
            from(tarTree(file))
            into(outputDir)
            exclude("docs/**", "debug/**", "tor/pluggable_transports/**")
        }
        // Manifest for jar/module-safe runtime extraction.
        val manifest = outputDir.file("manifest.txt").asFile
        outputDir.asFile
            .walkTopDown()
            .filter { it.isFile && it.name != "manifest.txt" }
            .sorted()
            .forEach {
                manifest.appendText(
                    outputDir.asFile
                        .toPath()
                        .relativize(it.toPath())
                        .toString() + "\n",
                )
            }
        println("Bundled Tor for $platform -> ${outputDir.asFile}")
    }
}

tasks.named("processResources") {
    dependsOn(downloadTor)
}

jlink {
    imageZip.set(layout.buildDirectory.file("distributions/app-${javafx.platform.classifier}.zip"))
    options.set(listOf("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages"))
    launcher {
        name = "anonymous"
    }
}
