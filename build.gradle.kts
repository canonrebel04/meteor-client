import java.io.File

plugins {
    alias(libs.plugins.fabric.loom)
    id("maven-publish")
}

val prismMinecraftDir = providers.gradleProperty("prismMinecraftDir")
    .orElse(providers.environmentVariable("PRISM_MINECRAFT_DIR"))
    .orElse("${System.getProperty("user.home")}/.local/share/PrismLauncher/instances/1.21.10/minecraft")

val prismModsDir = prismMinecraftDir.map { File(it, "mods") }

base {
    archivesName = properties["archives_base_name"] as String
    group = properties["maven_group"] as String

    val suffix = if (project.hasProperty("build_number")) {
        project.findProperty("build_number")
    } else {
        "local"
    }

    version = libs.versions.minecraft.get() + "-" + suffix
}

repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
    maven {
        name = "Terraformers"
        url = uri("https://maven.terraformersmc.com")
    }
    maven {
        name = "ViaVersion"
        url = uri("https://repo.viaversion.com")
    }
    mavenCentral()

    exclusiveContent {
        forRepository {
            maven {
                name = "modrinth"
                url = uri("https://api.modrinth.com/maven")
            }
        }
        filter {
            includeGroup("maven.modrinth")
        }
    }
}

val modInclude: Configuration by configurations.creating
val jij: Configuration by configurations.creating

configurations {
    // include mods
    modImplementation.configure {
        extendsFrom(modInclude)
    }
    include.configure {
        extendsFrom(modInclude)
    }

    // include libraries (jar-in-jar)
    implementation.configure {
        extendsFrom(jij)
    }
    include.configure {
        extendsFrom(jij)
    }
}

dependencies {
    // Fabric
    minecraft(libs.minecraft)
    mappings(variantOf(libs.yarn) { classifier("v2") })
    modImplementation(libs.fabric.loader)

    val fapiVersion = libs.versions.fabric.api.get()
    modInclude(fabricApi.module("fabric-api-base", fapiVersion))
    modInclude(fabricApi.module("fabric-resource-loader-v1", fapiVersion))

    // Compat fixes
    modCompileOnly(fabricApi.module("fabric-renderer-indigo", fapiVersion))
    modCompileOnly(libs.sodium) { isTransitive = false }
    modCompileOnly(libs.lithium) { isTransitive = false }
    modCompileOnly(libs.iris) { isTransitive = false }
    modCompileOnly(libs.viafabricplus) { isTransitive = false }
    modCompileOnly(libs.viafabricplus.api) { isTransitive = false }

    // Baritone API (compile-only)
    // Default: use published dependency from Meteor maven.
    // Optional override: pass -PbaritoneJar=/path/to/baritone.jar (e.g. your built standalone jar).
    val baritoneJarOverride = providers.gradleProperty("baritoneJar").orNull?.let(::File)
    val baritoneJarInPrism = prismModsDir.get().let { File(it, "baritone-standalone-fabric-${libs.versions.minecraft.get()}-SNAPSHOT.jar") }

    when {
        baritoneJarOverride != null && baritoneJarOverride.isFile -> modCompileOnly(files(baritoneJarOverride))
        baritoneJarInPrism.isFile -> modCompileOnly(files(baritoneJarInPrism))
        else -> modCompileOnly(libs.baritone)
    }
    modCompileOnly(libs.modmenu)

    // Libraries (JAR-in-JAR)
    jij(libs.orbit)
    jij(libs.starscript)
    jij(libs.discord.ipc)
    jij(libs.reflections)
    jij(libs.netty.handler.proxy) { isTransitive = false }
    jij(libs.netty.codec.socks) { isTransitive = false }
    jij(libs.waybackauthlib)

    // Fix "unknown enum constant Level.FULL" warnings (missing j2objc annotations at compile time).
    compileOnly("com.google.j2objc:j2objc-annotations:2.8")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

// Handle transitive dependencies for jar-in-jar
// Based on implementation from BaseProject by FlorianMichael/EnZaXD
// Source: https://github.com/FlorianMichael/BaseProject/blob/main/src/main/kotlin/de/florianmichael/baseproject/Fabric.kt
// Licensed under Apache License 2.0
afterEvaluate {
    val jijConfig = configurations.findByName("jij") ?: return@afterEvaluate

    // Dependencies to exclude from jar-in-jar
    val excluded = setOf(
        "org.slf4j",    // Logging provided by Minecraft
        "jsr305"        // Compile time annotations only
    )


    jijConfig.incoming.resolutionResult.allDependencies.forEach { dep ->
        val requested = dep.requested.displayName

        if (excluded.any { requested.contains(it) }) return@forEach

        val compileOnlyDep = dependencies.create(requested) {
            isTransitive = false
        }

        val implDep = dependencies.create(compileOnlyDep)

        dependencies.add("compileOnlyApi", compileOnlyDep)
        dependencies.add("implementation", implDep)
        dependencies.add("include", compileOnlyDep)
    }
}

loom {
    accessWidenerPath = file("src/main/resources/meteor-client.accesswidener")
}

afterEvaluate {
    tasks.migrateMappings.configure {
        outputDir.set(project.file("src/main/java"))
    }
}

tasks {
    processResources {
        val buildNumber = project.findProperty("build_number")?.toString() ?: ""
        val commit = project.findProperty("commit")?.toString() ?: ""

        val propertyMap = mapOf(
            "version" to project.version,
            "build_number" to buildNumber,
            "commit" to commit,
            "minecraft_version" to libs.versions.minecraft.get(),
            "loader_version" to libs.versions.fabric.loader.get()
        )

        inputs.properties(propertyMap)
        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    jar {
        inputs.property("archivesName", project.base.archivesName.get())

        from("LICENSE") {
            rename { "${it}_${inputs.properties["archivesName"]}" }
        }

        // Launch sub project
        dependsOn(":launch:compileJava")
        from(project(":launch").layout.buildDirectory.dir("classes/java/main"))

        manifest {
            attributes["Main-Class"] = "meteordevelopment.meteorclient.Main"
        }
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21

        if (System.getenv("CI")?.toBoolean() == true) {
            withSourcesJar()
            withJavadocJar()
        }
    }

    withType<JavaCompile> {
        options.release = 21
        options.compilerArgs.add("-Xlint:deprecation")
        options.compilerArgs.add("-Xlint:unchecked")
    }

    test {
        useJUnitPlatform()
    }

    val prismMinecraftDir = project.findProperty("prismMinecraftDir")?.toString()
        ?: System.getenv("PRISM_MINECRAFT_DIR")
        ?: "${System.getProperty("user.home")}/.local/share/PrismLauncher/instances/1.21.10/minecraft"
    register<Copy>("deployToPrism") {
        group = "deployment"
        description = "Builds the remapped jar and copies it into PrismLauncher instance mods/ (overwrites)."

        val remapJarTask = named("remapJar")
        dependsOn(remapJarTask)

        val archivesBaseNameValue = project.base.archivesName.get()
        val modsDir = File(prismMinecraftDir, "mods")

        from(remapJarTask)
        into(modsDir)

        doFirst {
            if (!modsDir.exists()) {
                throw GradleException(
                    "PrismLauncher mods dir not found: ${modsDir.absolutePath}. " +
                    "Set -PprismMinecraftDir=/path/to/PrismLauncher/instances/<instance>/minecraft " +
                    "or env PRISM_MINECRAFT_DIR."
                )
            }

            val targetName = remapJarTask.get().outputs.files.singleFile.name

            modsDir.listFiles()
                ?.filter { it.isFile }
                ?.filter {
                    (it.name == "$archivesBaseNameValue.jar") ||
                        (it.name.startsWith("$archivesBaseNameValue-") && it.name.endsWith(".jar"))
                }
                ?.filter { it.name != targetName }
                ?.forEach { it.delete() }
            
            println("Deploying to: ${modsDir.absolutePath}")
        }
    }

    javadoc {
        with(options as StandardJavadocDocletOptions) {
            addStringOption("Xdoclint:none", "-quiet")
            addStringOption("encoding", "UTF-8")
            addStringOption("charSet", "UTF-8")
        }
    }

    build {
        if (System.getenv("CI")?.toBoolean() == true) {
            dependsOn("javadocJar")
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "meteor-client"

            version = libs.versions.minecraft.get() + "-SNAPSHOT"
        }
    }

    repositories {
        maven("https://maven.meteordev.org/snapshots") {
            name = "meteor-maven"

            credentials {
                username = System.getenv("MAVEN_METEOR_ALIAS")
                password = System.getenv("MAVEN_METEOR_TOKEN")
            }

            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
}
