pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal { content { includeGroup("top.aidanrao") } }
        google(); mavenCentral()
    }
}
rootProject.name = "event-analytics-example"
include(":app")
