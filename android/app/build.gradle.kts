plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "net.ssync.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.ssync.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    flavorDimensions += "environment"
    productFlavors {
        create("staging") {
            dimension = "environment"
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            buildConfigField("String", "CONNECT_BASE_URL", "\"https://soulsync-connect-staging.fly.dev\"")
        }
        create("prod") {
            dimension = "environment"
            buildConfigField("String", "CONNECT_BASE_URL", "\"https://connect.ssync.net\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core:connect"))
    implementation(project(":core:storage"))
    implementation(project(":core:network"))
    implementation(project(":core:player"))
    implementation(project(":feature:pairing"))

    implementation(libs.androidx.core.ktx)
    // installs the baseline profile (src/main/baseline-prof.txt, and the ones compose ships) so
    // android precompiles the interface code instead of interpreting it: on a shield the app ran
    // interpreted, which was most of why the tv felt laggy
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")

    testImplementation(libs.junit4)
}
