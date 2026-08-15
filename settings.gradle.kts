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
        // Solo hace falta si en el futuro se usa el fork "apksig-android" de
        // MuntashirAkon mencionado en el README como alternativa si apksig
        // (Google) da problemas en ART.
        maven("https://jitpack.io")
    }
}

rootProject.name = "compilador-android"
include(":app")
