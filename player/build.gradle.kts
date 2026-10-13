plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.vibeplayer.player"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        minSdk = 37

        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters.add("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add("src/main/jniLibs")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
