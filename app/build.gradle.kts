import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val signingProperties = Properties().also { props ->
    val file = rootProject.file("signing.properties")
    if (file.exists()) file.inputStream().use { input -> props.load(input) }
}

fun signingValue(key: String, envName: String): String? =
    System.getenv(envName) ?: signingProperties.getProperty(key)

val githubOAuthClientId = providers.gradleProperty("POCKET_GITHUB_CLIENT_ID")
    .orElse(providers.environmentVariable("POCKET_GITHUB_CLIENT_ID"))
    .orElse("")
    .get()

android {
    namespace = "dev.pocketagent"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.pocketagent"
        minSdk = 26
        // Target a modern Android release so POST_NOTIFICATIONS can be requested
        // directly at runtime. Shared storage still uses the all-files app-op on
        // Android 11+ because the workspace needs arbitrary project directories.
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        buildConfigField(
            "String",
            "GITHUB_OAUTH_CLIENT_ID",
            "\"$githubOAuthClientId\"",
        )
    }

    signingConfigs {
        create("release") {
            val storePath = signingValue("storeFile", "ANDROID_SIGNING_STORE_FILE")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = signingValue("storePassword", "ANDROID_SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "ANDROID_SIGNING_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "ANDROID_SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    lint {
        // Side-loaded app: MANAGE_EXTERNAL_STORAGE is intentional for project workspaces,
        // not a Play release.
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(project(":terminal-view"))
    implementation(project(":terminal-emulator"))

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation(libs.kotlinx.coroutines.test)
}
