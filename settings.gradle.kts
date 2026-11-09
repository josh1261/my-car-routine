pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
        google(); mavenCentral()
    }
}
rootProject.name = "tanmankeum"
include(":app")
