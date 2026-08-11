// ⚠️ Модуль НЕ СОБРАН: Compose Multiplatform тянет AndroidX с dl.google.com, а окружение, в котором
// писался код, этот хост не пропускает. Первая сборка — на машине владельца.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
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
    // iosX64 намеренно нет: Compose Multiplatform 1.11 его не публикует.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":scanner-core"))
            api(project(":scanner-camera"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            // LocalLifecycleOwner с Compose 1.7 живёт здесь, а не в compose.ui;
            // без явной зависимости androidMain не компилируется.
            implementation(libs.jetbrains.lifecycle.runtime.compose)
        }
        androidMain.dependencies {
            implementation(libs.androidx.camera.view)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.core)
            // ContextCompat: полагаться на транзитив от CameraX нельзя — уедет при первом бампе.
            implementation(libs.androidx.core.ktx)
        }
    }
}

android {
    namespace = "com.ssh.scanner.ui"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
