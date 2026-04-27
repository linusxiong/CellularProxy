plugins {
    id("com.android.application") version "8.13.1" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    kotlin("jvm") version "2.2.21" apply false
    kotlin("android") version "2.2.21" apply false
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}
