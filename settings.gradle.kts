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

rootProject.name = "CellularProxy"

include(":shared-model")
include(":proxy-server")
include(":core-network")
include(":root-control")
include(":cloudflare-tunnel")
include(":app")
