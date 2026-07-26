import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

group = "org.example.overlay"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    // Версию material3 задаёт сам плагин Compose: с 1.9 она разошлась с версией CMP,
    // и явные координаты `org.jetbrains.compose.material3:material3:1.11.1` не существуют.
    @Suppress("DEPRECATION")
    implementation(compose.material3)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.markdown)
    implementation(libs.jsoup)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "org.example.overlay.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "mcp-destiny2-gui"
            packageVersion = "1.0.0"
            vendor = "greenie"
            // Только латиница: WiX кодирует строки установщика в кодовой странице культуры
            // (en-us), и кириллица валит сборку .msi с LGHT0311.
            description = "Voice overlay for Destiny 2"

            windows {
                // Фиксированный UUID: без него каждая сборка ставится как новый продукт.
                upgradeUuid = "6f1b0a54-6d1e-4b23-9a2f-8f0d3c7b1e42"
                menu = true
                shortcut = true
                console = false
            }
        }
    }
}
