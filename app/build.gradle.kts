import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI sets these from scripts/version.sh; local builds use the dev defaults.
val appVersionName = providers.environmentVariable("GPS_CAM_VERSION_NAME").orElse("0.1.0-dev").get()
val appVersionCode = providers.environmentVariable("GPS_CAM_VERSION_CODE").orElse("1").get().toInt()
// Release signing: CI decodes the keystore from repository secrets. Without it, release APKs are unsigned.
val releaseKeystorePath = providers.environmentVariable("GPS_CAM_KEYSTORE_PATH").orNull?.takeIf { it.isNotEmpty() }

android {
    namespace = "io.github.swidegit.gpscam"
    compileSdk = 37
    signingConfigs {
        if (releaseKeystorePath != null) {
            fun signingEnv(name: String): String =
                providers.environmentVariable(name).orNull?.takeIf { it.isNotEmpty() }
                    ?: throw GradleException("GPS_CAM_KEYSTORE_PATH is set but $name is missing; set all release signing variables")
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = signingEnv("GPS_CAM_KEYSTORE_PASSWORD")
                keyAlias = signingEnv("GPS_CAM_KEY_ALIAS")
                keyPassword = signingEnv("GPS_CAM_KEY_PASSWORD")
            }
        }
    }
    defaultConfig {
        applicationId = "io.github.swidegit.gpscam"
        // Android 10+: MediaStore RELATIVE_PATH without storage permissions.
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

dependencies {
    val camerax = "1.6.2"
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.camera:camera-effects:$camerax")
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
