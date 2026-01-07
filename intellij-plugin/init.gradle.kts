allprojects {
    repositories {
        mavenCentral()
        maven {
            url = uri("http://mvnrepo.alibaba-inc.com/mvn/repository")
            isAllowInsecureProtocol = true
        }
        maven {
            url = uri("https://cache-redirector.jetbrains.com/intellij-dependencies")
        }
        maven {
            url = uri("https://www.jetbrains.com/intellij-repository/releases")
        }
    }
}
