plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.senzaproject.camera"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.senzaproject.camera"
        minSdk = 34
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"
    }

    signingConfigs {
        create("senza") {
            storeFile = file("senza.jks")
            storePassword = "senzacamera"
            keyAlias = "senza"
            keyPassword = "senzacamera"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("senza")
        }
        debug {
            signingConfig = signingConfigs.getByName("senza")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
