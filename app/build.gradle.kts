plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.room)
    alias(libs.plugins.ksp)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.vibeplayer"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.vibeplayer"
        minSdk = 37
        targetSdk = 37
        versionCode = 6
        versionName = "0.8.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    signingConfigs {
        create("local") {
            // Personal app, never published: debug and release are both signed with the Android debug
            // key of this PC, the key the app on the phone was first installed with. Updates therefore
            // always install in place and keep history and settings. Back up ~/.android/debug.keystore.
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("local")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ""
            signingConfig = signingConfigs.getByName("local")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // ./gradlew :app:testDebugUnitTest -Proborazzi.record=true writes the PNGs under build/outputs/roborazzi;
            // without it the screenshot tests only render (no files, no comparison).
            it.systemProperty("roborazzi.test.record", project.findProperty("roborazzi.record") ?: "false")
            // Robolectric sets FileDescriptor internals (shared memory) on JDK 17+.
            it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
            // Robolectric downloads its Android runtime from Maven Central; -Probolectric.repo=<url> uses a mirror.
            project.findProperty("robolectric.repo")?.let { url -> it.systemProperty("robolectric.dependency.repo.url", url) }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":player"))
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // P13 network sources: SFTP, SMB 2/3, FTP (HTTP, WebDAV, DLNA and M3U use the platform's HttpURLConnection)
    implementation(libs.jsch)
    implementation(libs.smbj)
    implementation(libs.commons.net)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    // Screenshot tests of composables on the JVM (Robolectric + Roborazzi); see ScreenshotTest.
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // ui-test-junit4 brings Espresso 3.5, which calls InputManager.getInstance() (gone in Android 17).
    testImplementation("androidx.test.espresso:espresso-core:3.7.0")
    testImplementation("androidx.test:core:1.7.0")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

// Baseline profile generation and Macrobenchmark runs install the plugin-made nonMinifiedRelease /
// benchmarkRelease variants. Give those a separate applicationId so a device run can never reinstall
// over, clear or otherwise touch the real com.vibeplayer (watch history lives there). The profile
// itself is keyed by class/method names, not applicationId, so it applies to the real release build.
androidComponents {
    onVariants { variant ->
        val bt = variant.buildType
        if (bt == "nonMinifiedRelease" || bt == "benchmarkRelease") {
            variant.applicationId.set("com.vibeplayer.benchmark")
        }
    }
}
