// Гейт целей сборки: `core` собирает только чистое ядро (JVM), `all` — все модули.
//
// Зачем: ядро не зависит ни от Android SDK, ни от Compose, поэтому его можно собрать и
// прогнать тестами в окружении без Android-тулчейна и без доступа к репозиторию Google.
// Полная сборка (`all`, значение по умолчанию) требует Android SDK, а iOS-таргеты — macOS.
//
//   gradle -Pscanner.targets=core :scanner-core:jvmTest
//   gradle build                                   // то же, что -Pscanner.targets=all

pluginManagement {
    val fullBuild = (providers.gradleProperty("scanner.targets").orNull ?: "all") == "all"
    repositories {
        mavenCentral()
        gradlePluginPortal()
        if (fullBuild) {
            google {
                mavenContent {
                    includeGroupAndSubgroups("androidx")
                    includeGroupAndSubgroups("com.android")
                    includeGroupAndSubgroups("com.google")
                }
            }
        }
    }
}

dependencyResolutionManagement {
    val fullBuild = (providers.gradleProperty("scanner.targets").orNull ?: "all") == "all"
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        if (fullBuild) {
            google {
                mavenContent {
                    includeGroupAndSubgroups("androidx")
                    includeGroupAndSubgroups("com.android")
                    includeGroupAndSubgroups("com.google")
                }
            }
        }
    }
}

rootProject.name = "scanner"

include(":scanner-core")

if ((providers.gradleProperty("scanner.targets").orNull ?: "all") == "all") {
    include(":scanner-camera")
    include(":scanner-ui")
    // Сэмпла здесь нет намеренно: на AGP 9 app-модуль со встроенным Kotlin несовместим
    // с KGP в корневом класслоадере, который нужен KMP-библиотекам. Сэмпл — отдельный
    // композитный билд в sample-android/ со своим settings.gradle.kts.
}
