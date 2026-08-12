// Сэмпл — чистый Android-модуль и ОТДЕЛЬНЫЙ билд (см. settings.gradle.kts рядом).
// Открывать его в IDE как самостоятельный проект: File → Open → каталог sample-android.
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
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Версию подменяет композитный билд — важны только координаты.
    implementation("com.ssh.scanner:scanner-ui:0.1.0-SNAPSHOT")
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(libs.androidx.activity.compose)
}
