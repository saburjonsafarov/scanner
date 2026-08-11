// KGP и Compose-плагины объявлены в корне с `apply false` намеренно: так их классы живут
// в одном родительском класслоадере для всех модулей. Без этого Gradle поднимает KGP отдельным
// лоадером на каждый уникальный набор плагинов модуля, и общий build-сервис KGP падает
// ClassCastException «SwiftPMLockTaskAggregationBuildService … cannot be cast to …» —
// поймано прогоном CI №3. Все три плагина лежат на Maven Central, поэтому гейту
// -Pscanner.targets=core они не мешают. AGP здесь НЕТ сознательно: он резолвится только
// с Google Maven, а core-гейт обязан конфигурироваться без него.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

allprojects {
    group = "com.ssh.scanner"
    // Версию задаёт релизный workflow из имени тега (`v0.1.0` → `-Pscanner.version=0.1.0`);
    // всё, что собрано не с тега, остаётся снапшотом.
    version = providers.gradleProperty("scanner.version").orNull ?: "0.1.0-SNAPSHOT"
}

// Публикация настраивается один раз на все модули, где подключён maven-publish.
// Площадка — GitHub Packages: тот же путь, что у compose-graph; смена площадки — это
// смена одного url, а не переписывание конфигурации.
subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri(
                        "https://maven.pkg.github.com/" +
                            (providers.environmentVariable("GITHUB_REPOSITORY").orNull
                                ?: "saburjonsafarov/scanner"),
                    )
                    credentials {
                        username = providers.environmentVariable("GITHUB_ACTOR").orNull
                        password = providers.environmentVariable("GITHUB_TOKEN").orNull
                    }
                }
            }
        }
    }
}
