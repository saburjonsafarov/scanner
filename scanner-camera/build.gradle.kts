// ⚠️ Модуль НЕ СОБРАН: писался в окружении без Android SDK и без доступа к репозиторию Google.
// Первая сборка — на машине с Android-тулчейном.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    `maven-publish`
}

kotlin {
    explicitApi()

    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        // Без этого androidTarget не попадает в публикацию вовсе — KMP публикует только
        // явно выбранные варианты Android-библиотеки.
        publishLibraryVariants("release")
    }
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain.dependencies {
            api(project(":scanner-core"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.camera.core)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
        }
    }
}

android {
    namespace = "com.ssh.scanner.camera"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
