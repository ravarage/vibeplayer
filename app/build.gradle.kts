plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.room)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.vibeplayer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vibeplayer"
        minSdk = 37
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":player"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    debugImplementation(libs.compose.ui.tooling)
}
