plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.cellularproxy.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cellularproxy"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared-model"))
    implementation(project(":core-network"))
    implementation(project(":proxy-server"))
    implementation(project(":root-control"))
    implementation(project(":cloudflare-tunnel"))
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.9.6")

    testImplementation(kotlin("test-junit"))
}
