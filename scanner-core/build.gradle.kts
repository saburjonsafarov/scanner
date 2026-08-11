import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
}

/** Имя фреймворка, под которым ядро импортируется в Swift: `import ScannerCore`. */
val XCFRAMEWORK_NAME = "ScannerCore"

// Ядро намеренно не объявляет androidTarget: в нём нет ни одного Android API, а Android-потребитель
// штатно резолвит jvm-вариант (тот же механизм, которым Android-модули едят kotlinx-coroutines).
// Меньше таргетов — меньше поверхность сборки и никакой зависимости от AGP.
val fullBuild = (providers.gradleProperty("scanner.targets").orNull ?: "all") == "all"

kotlin {
    explicitApi()

    // jvmToolchain здесь не пинится намеренно: в CI-окружении может стоять единственный JDK 21,
    // и запрос другого тулчейна валит конфигурацию. Достаточно задать целевой байткод.
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    if (fullBuild) {
        // Compose сюда не тянется, поэтому iosX64 публикуется без ограничений Compose Multiplatform.
        //
        // XCFramework — это первый из двух путей потребления на iOS: нативный проект берёт чистый
        // декодер и рисует свой интерфейс на SwiftUI. Второй путь (готовый экран через
        // ComposeUIViewController) запланирован в :scanner-ui, iOS-исходников там пока нет.
        val xcframework = XCFramework(XCFRAMEWORK_NAME)
        listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
            target.binaries.framework {
                baseName = XCFRAMEWORK_NAME
                isStatic = true
                xcframework.add(this)
            }
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
