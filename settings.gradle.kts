pluginManagement {
    repositories {
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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 네이버 지도 SDK 전용 저장소 (mavenCentral 미배포)
        maven("https://repository.map.naver.com/archive/maven")
    }
}

rootProject.name = "moyeota-driver"
include(":app")
include(":core:designsystem")
include(":domain")
include(":data")
include(":presentation")
