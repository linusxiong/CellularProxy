plugins {
    id("com.android.application")
    kotlin("android")
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
    implementation(project(":cloudflare-tunnel"))
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    testImplementation(kotlin("test-junit"))
}
