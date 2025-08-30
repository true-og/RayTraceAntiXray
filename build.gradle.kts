/* This is free and unencumbered software released into the public domain */

import org.gradle.kotlin.dsl.provideDelegate

/* ------------------------------ Plugins ------------------------------ */
plugins {
    id("java") // Import Java plugin.
    id("java-library") // Import Java Library plugin.
    id("com.diffplug.spotless") version "7.0.4" // Import Spotless plugin.
    id("com.gradleup.shadow") version "8.3.6" // Import Shadow plugin.
    id("checkstyle") // Import Checkstyle plugin.
    eclipse // Import Eclipse plugin.
    kotlin("jvm") version "2.1.21" // Import Kotlin JVM plugin.
    id("maven-publish")
}

extra["kotlinAttribute"] = Attribute.of("kotlin-tag", Boolean::class.javaObjectType)

val kotlinAttribute: Attribute<Boolean> by rootProject.extra

/* --------------------------- JDK / Kotlin ---------------------------- */
java {
    sourceCompatibility = JavaVersion.VERSION_17 // Compile with JDK 17 compatibility.
    toolchain { // Select Java toolchain.
        languageVersion.set(JavaLanguageVersion.of(17)) // Use JDK 17.
        vendor.set(JvmVendorSpec.GRAAL_VM) // Use GraalVM CE.
    }
}

kotlin { jvmToolchain(17) }

/* ----------------------------- Metadata ------------------------------ */
group = "com.vanillage.raytraceantixray" // Declare bundle identifier.

version = "1.10.8" // Declare plugin version (will be in .jar).

val apiVersion = "1.19" // Declare minecraft server target version.
val minecraftVersion = "1.19.4"
val paperVersion = "${minecraftVersion}-R0.1-SNAPSHOT"

/* ----------------------------- Resources ----------------------------- */
tasks.named<ProcessResources>("processResources") {
    val props = mapOf("version" to version, "apiVersion" to apiVersion)
    inputs.properties(props) // Indicates to rerun if version changes.
    filesMatching("plugin.yml") { expand(props) }
    from("LICENSE") { into("/") } // Bundle licenses into jarfiles.
}

/* ---------------------------- Repos ---------------------------------- */
repositories {
    mavenCentral() // Import the Maven Central Maven Repository.
    gradlePluginPortal() // Import the Gradle Plugin Portal Maven Repository.
    maven { url = uri("https://repo.purpurmc.org/snapshots") } // Import the PurpurMC Maven Repository.
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
    maven { url = uri("https://libraries.minecraft.net/") }
    maven { url = uri("https://repo.dmulloy2.net/repository/public/") }
    maven { url = uri("file://${System.getProperty("user.home")}/.m2/repository") }
    System.getProperty("SELF_MAVEN_LOCAL_REPO")?.let { // TrueOG Bootstrap mavenLocal().
        val dir = file(it)
        if (dir.isDirectory) {
            println("Using SELF_MAVEN_LOCAL_REPO at: $it")
            maven { url = uri("file://${dir.absolutePath}") }
        } else {
            logger.error("TrueOG Bootstrap not found, defaulting to ~/.m2 for mavenLocal()")
            mavenLocal()
        }
    } ?: logger.error("TrueOG Bootstrap not found, defaulting to ~/.m2 for mavenLocal()")
}

/* ---------------------- Dev-bundle prepare --------------------------- */
val devBundle by configurations.creating

dependencies { add("devBundle", "io.papermc.paper:dev-bundle:${paperVersion}@zip") }

val devBundleDir = layout.buildDirectory.dir("paper-dev-bundle")
val unpackDevBundle by
    tasks.registering(Copy::class) {
        from({ zipTree(devBundle.singleFile) })
        into(devBundleDir)
    }
val patchPaper by
    tasks.registering(Exec::class) {
        dependsOn(unpackDevBundle)
        doFirst {
            workingDir = devBundleDir.get().asFile
            commandLine(
                "java",
                "-Dpaperclip.patchonly=true",
                "-jar",
                devBundleDir.get().file("data/paperclip-mojang+yarn.jar").asFile.absolutePath,
            )
        }
    }
val mojangMappedPaperJar = devBundleDir.map { it.file("versions/${minecraftVersion}/paper-${minecraftVersion}.jar") }

/* ---------------------- Java project deps ---------------------------- */
dependencies {
    compileOnly("org.purpurmc.purpur:purpur-api:1.19.4-R0.1-SNAPSHOT") // Declare Purpur API version to be packaged.
    compileOnly("io.papermc.paper:paper-api:${paperVersion}")
    compileOnly("io.papermc.paper:paper-mojangapi:${paperVersion}")
    add("compileOnly", files(mojangMappedPaperJar).builtBy(patchPaper))
    compileOnly("com.mojang:datafixerupper:6.0.6")
    compileOnly("io.netty:netty-all:4.1.87.Final")
    compileOnly(files("libs/ProtocolLib-5.0.jar")) // Import Legacy ProtocolLib API.
}

/* ---------------------- Reproducible jars ---------------------------- */
tasks.withType<AbstractArchiveTask>().configureEach { // Ensure reproducible .jars
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

/* ----------------------------- Shadow -------------------------------- */
tasks.shadowJar {
    exclude("io.github.miniplaceholders.*") // Exclude the MiniPlaceholders package from being shadowed.
    archiveClassifier.set("shadow") // Use empty string instead of null.
    minimize()
}

tasks.jar { archiveClassifier.set("part") } // Applies to root jarfile only.

/* --------------------------- Tiny Remapper --------------------------- */
val tinyRemapper by configurations.creating

dependencies { add("tinyRemapper", "net.fabricmc:tiny-remapper:0.8.6:fat") }

val mojangMappedJar by
    tasks.registering(Jar::class) {
        dependsOn(patchPaper)
        from(sourceSets.main.get().output)
        archiveClassifier.set("mojang-mapped")
    }

val remappedJar = layout.buildDirectory.file("libs/${project.name}-${project.version}.jar")

val tinyRemapJar by
    tasks.registering(JavaExec::class) {
        dependsOn(mojangMappedJar, patchPaper)
        mainClass.set("net.fabricmc.tinyremapper.Main")
        classpath = tinyRemapper
        val input = mojangMappedJar.flatMap { it.archiveFile }
        val mappings = devBundleDir.map { it.file("data/mojang+yarn-spigot-reobf.tiny") }
        val paperJar = mojangMappedPaperJar
        args(
            input.get().asFile.absolutePath,
            remappedJar.get().asFile.absolutePath,
            mappings.get().asFile.absolutePath,
            "mojang+yarn",
            "spigot",
            paperJar.get().asFile.absolutePath,
        )
    }

val copyJarToTarget by
    tasks.registering(Copy::class) {
        dependsOn(tinyRemapJar)
        from(remappedJar)
        into(layout.projectDirectory.dir("target"))
    }

/* --------------------------- Build wiring ---------------------------- */
tasks.build { dependsOn(tasks.spotlessApply, tinyRemapJar, copyJarToTarget) } // Build depends on spotless and shadow.

/* --------------------------- Javac opts ------------------------------- */
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters") // Enable reflection for java code.
    options.isFork = true // Run javac in its own process.
    options.compilerArgs.add("-Xlint:deprecation") // Trigger deprecation warning messages.
    options.encoding = "UTF-8" // Use UTF-8 file encoding.
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach { dependsOn(patchPaper) }

/* ----------------------------- Auto Formatting ------------------------ */
spotless {
    java {
        eclipse().configFile("config/formatter/eclipse-java-formatter.xml") // Eclipse java formatting.
        leadingTabsToSpaces() // Convert leftover leading tabs to spaces.
        removeUnusedImports() // Remove imports that aren't being called.
    }
    kotlinGradle {
        ktfmt().kotlinlangStyle().configure { it.setMaxWidth(120) } // JetBrains Kotlin formatting.
        target("build.gradle.kts", "settings.gradle.kts") // Gradle files to format.
    }
}

checkstyle {
    toolVersion = "10.18.1" // Declare checkstyle version to use.
    configFile = file("config/checkstyle/checkstyle.xml") // Point checkstyle to config file.
    isIgnoreFailures = true // Don't fail the build if checkstyle does not pass.
    isShowViolations = true // Show the violations in any IDE with the checkstyle plugin.
}

tasks.named("compileJava") {
    dependsOn("spotlessApply") // Run spotless before compiling with the JDK.
}

tasks.named("spotlessCheck") {
    dependsOn("spotlessApply") // Run spotless before checking if spotless ran.
}
