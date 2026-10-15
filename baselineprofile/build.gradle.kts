plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.vibeplayer.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 37
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // StartupBenchmark measures cold-start numbers and needs a rooted/userdebug device. On a
        // normal phone MacrobenchmarkRule skips it via assumeTrue, and JUnit reports that assumption
        // violation as a failure, which fails the whole connected test run (and with it the profile
        // generation) before the profile is written. The profile itself only needs
        // BaselineProfileGenerator, so exclude the benchmark class from the instrumentation run.
        testInstrumentationRunnerArguments["notClass"] =
            "com.vibeplayer.baselineprofile.StartupBenchmark"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
}

baselineProfile {
    // Real phone, no Gradle Managed Device.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
