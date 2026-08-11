// Сэмпл — чистый Android-модуль, НЕ Kotlin Multiplatform.
//
// Это не упрощение, а требование AGP 9: Kotlin в нём встроен, и связка
// `com.android.application` + `org.jetbrains.kotlin.multiplatform` падает на конфигурации
// (ClassCastException в AgpWithBuiltInKotlinAppliedCheck — поймано первым прогоном CI).
// Compose-зависимости остаются через DSL плагина Compose Multiplatform.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

android {
    namespace = "com.ssh.scanner.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ssh.scanner.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = project.version.toString()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":scanner-ui"))
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(libs.androidx.activity.compose)
}
