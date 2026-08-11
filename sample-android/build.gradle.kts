// ⚠️ Модуль НЕ СОБРАН: писался в окружении без Android SDK.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    androidTarget()

    sourceSets {
        androidMain.dependencies {
            implementation(project(":scanner-ui"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(libs.androidx.activity.compose)
        }
    }
}

android {
    namespace = "com.ssh.scanner.sample"
    compileSdk = 36

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
