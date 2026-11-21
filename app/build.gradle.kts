import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
}

/** The app's version, `version.json`'s, which a release raises. */
val release = (JsonSlurper().parse(rootProject.file("version.json")) as Map<*, *>)["version"] as String
val (major, minor, patch) = release.split(".").map(String::toInt)

/**
 * The upload key a release is signed with for Google Play, which keeps the key that signs what it
 * hands out. It is never in the repository: a release build reads where it is and its password from
 * the environment, and is left unsigned without them.
 */
val uploadKeystore: String? = System.getenv("EIN_UPLOAD_KEYSTORE")

android {
    namespace = "eu.einhq.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "eu.einhq.app"
        minSdk = 29
        targetSdk = 37
        versionName = release
        // Google Play takes each upload's code only once and only rising: 1.2.3 is 1002003.
        versionCode = major * 1_000_000 + minor * 1_000 + patch
    }

    signingConfigs {
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = file(uploadKeystore)
                storePassword = System.getenv("EIN_UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("EIN_UPLOAD_KEY_ALIAS") ?: "upload"
                keyPassword = System.getenv("EIN_UPLOAD_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (uploadKeystore != null) signingConfig = signingConfigs.getByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        warningsAsErrors = true
        // Whether a newer library, Gradle plugin or Android version is out says nothing about this
        // code, and would fail a check on a day nothing here changed.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi")
    }
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)
    implementation(libs.play.services.code.scanner)
    testImplementation(libs.junit)
}
