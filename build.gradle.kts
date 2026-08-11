// Плагины намеренно не объявляются здесь даже с `apply false`: объявление в корне заставляет
// Gradle резолвить их при любой сборке, а AGP и Compose недоступны, когда собирается только ядро
// (-Pscanner.targets=core). Каждый модуль подключает то, что ему нужно, сам.

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
