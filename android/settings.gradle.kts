pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SoulSyncAndroid"

include(":app")
include(":core:connect")
include(":core:storage")
include(":core:network")
include(":core:player")
include(":feature:pairing")
