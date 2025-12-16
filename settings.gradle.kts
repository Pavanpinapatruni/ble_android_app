pluginManagement {
    repositories {
        maven { url = uri("https://rb-artifactory.bosch.com:443/artifactory/maven-central-remote/") }
        maven { url = uri("https://rb-artifactory.bosch.com:443/artifactory/maven-central-remote-cache/") }
        maven { url = uri("https://rb-artifactory.bosch.com:443/artifactory/maven-google-remote-cache/") }
        maven { url = uri("https://rb-artifactory.bosch.com:443/artifactory/maven-google-remote/") }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://rb-artifactory.bosch.com:443/artifactory/maven-central-remote/") }
        maven { url = uri("https://rb-artifactory.bosch.com:443/artifactory/maven-central-remote-cache/") }
        maven { url = uri("https://rb-artifactory.bosch.com:443/artifactory/maven-google-remote-cache/") }
        maven { url = uri("https://rb-artifactory.bosch.com:443/artifactory/maven-google-remote/") }
        google()
        mavenCentral()
    }
}

rootProject.name = "ble"
include(":app")
