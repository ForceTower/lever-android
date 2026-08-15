plugins {
    alias(libs.plugins.android.application)
    // `json` keys resolve their serializer at the declaration site, so a module
    // that declares one needs kotlinx.serialization's compiler plugin — exactly
    // as a consumer of the library does.
    alias(libs.plugins.kotlin.serialization)
}

// The consumer fixture: an app module that consumes the library the way a real
// dependency is consumed, declares a `Flags` object, and reads through the
// operator. Building the library itself proves nothing about the consumption
// promise (spec 0003 §1).
android {
    namespace = "dev.forcetower.lever.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.forcetower.lever.sample"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // M9's R8 check: the AAR's consumer rules must survive minification.
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)

    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(project(":lever"))
}
