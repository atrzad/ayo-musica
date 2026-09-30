plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing comes from the environment (GitHub secrets or a local keystore); without it the
// release build is signed with the debug key so it still installs for testing.
val keystore = System.getenv("AYO_KEYSTORE")

android {
    namespace = "io.github.atrzad.ayomusica"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.atrzad.ayomusica"
        minSdk = 26
        targetSdk = 37
        versionCode = (System.getenv("AYO_VERSION_CODE") ?: "1").toInt()
        versionName = "0.3.0"
    }

    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("AYO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AYO_KEY_ALIAS") ?: "ayo-musica"
                keyPassword = System.getenv("AYO_KEY_PASSWORD") ?: System.getenv("AYO_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
