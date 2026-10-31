import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.veuroutes"
version = "1.3.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val webstormPath: String? = providers.gradleProperty("webstormPath").orNull

dependencies {
    intellijPlatform {
        if (webstormPath != null) {
            local(webstormPath)
        } else {
            webstorm("2026.2.1")
        }
    }

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

intellijPlatform {
    pluginConfiguration {
        name = "Vue Routes List"
        ideaVersion {
            sinceBuild = "251"
            untilBuild = "262.*"
        }
    }

    buildSearchableOptions = false
}

// The IntelliJ Platform Gradle plugin aligns Kotlin compilation with the target IDE's JBR
// (Java 25 for WebStorm 2026.2). The plugin is built against Java 21 so it also loads in
// older IDEs covered by `sinceBuild`, so pin every Kotlin compile task explicitly.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}

tasks.test {
    useJUnitPlatform()
}
