buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // The kotlinx validator's dump engine, driven directly by :lever's ABI
        // tasks — its Gradle plugin cannot see AGP 9's built-in Kotlin.
        classpath(libs.binary.compatibility.validator)
        classpath(libs.kotlin.metadata.jvm)
        classpath(libs.asm.tree)
    }
}

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    // AGP 9 compiles Kotlin itself; this stays on the build classpath,
    // unapplied, so the compiler version is an exact pin in the version catalog
    // rather than whatever the Android plugin happens to ship (spec 0003 §1).
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.maven.publish) apply false
}
