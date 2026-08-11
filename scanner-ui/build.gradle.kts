
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // KMP-вариант Android-плагина — см. комментарий в scanner-camera.
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

kotlin {
    explicitApi()

    androidLibrary {
        namespace = "com.ssh.scanner.ui"
        compileSdk = 36
        minSdk = 24
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
