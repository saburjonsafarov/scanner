// Сэмпл — ОТДЕЛЬНЫЙ билд, а не модуль корневого. Это не причуда: на AGP 9 app-модуль
// со встроенным Kotlin не может жить в одном билде с KMP-библиотеками — им нужен KGP
// в корневом класслоадере, а app-модуль, увидев внешний KGP, падает на выпиленном
// BaseVariant (прогоны CI №4–№5 и воспроизведение на машине владельца). Композитный билд
// изолирует класспуты: библиотеки собираются своим билдом со своим KGP, сэмпл — своим.
pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "scanner-sample"

// Библиотеки приходят композитом: зависимости com.ssh.scanner:* подменяются на проекты
// корневого билда автоматически, по совпадению координат.
includeBuild("..")
