import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("com.google.devtools.ksp") version "2.1.0-1.0.28"
}

android {
    namespace = "com.lumeter"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lumeter"
        minSdk = 28
        targetSdk = 36
        versionCode = 6
        versionName = "0.7.0"
    }

    val signing = Properties().apply {
        val props = rootProject.file("local.properties")
        if (props.exists()) props.inputStream().use { load(it) }
    }

    signingConfigs {
        create("release") {
            val store = signing.getProperty("lumeter.release.store")
            if (store != null) {
                storeFile = file(store)
                storePassword = signing.getProperty("lumeter.release.storePassword")
                keyAlias = signing.getProperty("lumeter.release.keyAlias")
                keyPassword = signing.getProperty("lumeter.release.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
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
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)

    // CameraX
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    // Room database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore
    implementation(libs.datastore.preferences)
    
    // Serialization
    implementation(libs.kotlinx.serialization.json)
    
    // Permissions
    implementation(libs.accompanist.permissions)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
