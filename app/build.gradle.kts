import java.util.Properties

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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val e2eLocalProperties =
            Properties().apply {
                val file =
                    rootProject.layout.projectDirectory
                        .file("e2e.local.properties")
                        .asFile
                if (file.isFile) {
                    file.inputStream().use { input -> load(input) }
                }
            }

        fun cloudflareE2eValue(
            localProperty: String,
            environmentVariable: String,
            gradleProperty: String,
        ): String? = e2eLocalProperties
            .getProperty(localProperty)
            .trimmedOrNull()
            ?: providers
                .environmentVariable(environmentVariable)
                .orNull
                .trimmedOrNull()
            ?: providers
                .gradleProperty(gradleProperty)
                .orNull
                .trimmedOrNull()

        mapOf(
            "cloudflareTunnelToken" to
                cloudflareE2eValue(
                    localProperty = "cellularproxy.e2e.cloudflareTunnelToken",
                    environmentVariable = "CELLULARPROXY_E2E_CLOUDFLARE_TUNNEL_TOKEN",
                    gradleProperty = "cellularproxy.e2e.cloudflareTunnelToken",
                ),
            "cloudflareManagementHostname" to
                cloudflareE2eValue(
                    localProperty = "cellularproxy.e2e.cloudflareManagementHostname",
                    environmentVariable = "CELLULARPROXY_E2E_CLOUDFLARE_MANAGEMENT_HOSTNAME",
                    gradleProperty = "cellularproxy.e2e.cloudflareManagementHostname",
                ),
            "cloudflareManagementApiToken" to
                cloudflareE2eValue(
                    localProperty = "cellularproxy.e2e.cloudflareManagementApiToken",
                    environmentVariable = "CELLULARPROXY_E2E_MANAGEMENT_API_TOKEN",
                    gradleProperty = "cellularproxy.e2e.cloudflareManagementApiToken",
                ),
        ).forEach { (argumentName, value) ->
            if (value != null) {
                testInstrumentationRunnerArguments[argumentName] = value
            }
        }
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
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.9.6")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(kotlin("test-junit"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.03.01"))
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation(platform("androidx.compose:compose-bom:2026.03.01"))
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

private fun String?.trimmedOrNull(): String? = this
    ?.trim()
    ?.takeIf(String::isNotEmpty)
