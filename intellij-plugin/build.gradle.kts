plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "io.leavesfly"
version = "0.1.0"

repositories {
    mavenCentral()
    maven {
        url = uri("https://cache-redirector.jetbrains.com/intellij-dependencies")
    }
}

intellij {
    localPath.set("/Applications/IntelliJ IDEA.app/Contents")
    plugins.set(listOf())
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set("231")
        untilBuild.set("241.*")
    }

    buildSearchableOptions {
        enabled = false
    }
    
    runIde {
        jvmArgs = listOf("-Xmx2048m")
    }
}
