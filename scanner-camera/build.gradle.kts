
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Именно KMP-вариант Android-плагина: обычный com.android.library несовместим
    // с kotlin.multiplatform начиная с AGP 9.0 (поймано прогоном CI №2).
    alias(libs.plugins.android.kmp.library)
    `maven-publish`
}

kotlin {
    explicitApi()

    androidLibrary {
        namespace = "com.ssh.scanner.camera"
        compileSdk = 36
        minSdk = 24
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
