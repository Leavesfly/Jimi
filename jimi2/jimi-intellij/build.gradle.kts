plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.16.1"
}

group = "io.leavesfly.jimi"
version = "2.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    // ADK 依赖（通过 Maven 本地仓库）
    implementation("io.leavesfly.jimi:adk-api:0.1.0-SNAPSHOT")
    implementation("io.leavesfly.jimi:adk-core:0.1.0-SNAPSHOT")
    implementation("io.leavesfly.jimi:adk-llm:0.1.0-SNAPSHOT")
    implementation("io.leavesfly.jimi:adk-tools:0.1.0-SNAPSHOT")
    implementation("io.leavesfly.jimi:adk-tools-extended:0.1.0-SNAPSHOT")
    
    // Reactor
    implementation("io.projectreactor:reactor-core:3.6.0")
    
    // HTTP Client (adk-llm 运行时依赖)
    implementation("org.springframework:spring-webflux:6.1.2")
    implementation("io.projectreactor.netty:reactor-netty-http:1.1.14")
    
    // Cache (LLMFactory 依赖)
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    
    // Jackson
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.0")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.16.0")
    
    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
}

// IntelliJ 平台配置
intellij {
    version.set("2023.3")
    type.set("IC") // IntelliJ IDEA Community Edition
    plugins.set(listOf())
}

tasks {
    // JVM 版本
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
        options.encoding = "UTF-8"
    }

    patchPluginXml {
        sinceBuild.set("233")
        untilBuild.set("241.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
