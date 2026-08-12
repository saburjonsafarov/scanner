// В корне объявлен РОВНО один плагин, и это выверено тремя красными прогонами CI:
//
// - KGP обязан быть здесь: без него Gradle поднимает KGP отдельным класслоадером на каждый
//   уникальный набор плагинов модуля, и общий build-сервис KGP падает ClassCastException
//   «SwiftPMLockTaskAggregationBuildService … cannot be cast to …» (прогон №3).
// - AGP и Compose-плагинов здесь быть НЕ должно: корневой лоадер не видит дочерние, поэтому
//   CMP-плагин из корня не находит классы AGP, живущего в лоадере модуля, —
//   ClassNotFoundException «KotlinMultiplatformAndroidComponentsExtension» (прогон №6).
//   CMP живёт только в scanner-ui и сэмпле: один модульный лоадер, где видны и KGP (родитель),
//   и AGP (сосед по classpath). AGP вдобавок резолвится только с Google Maven, а гейт
//   -Pscanner.targets=core обязан конфигурироваться без него.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
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
