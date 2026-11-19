plugins {
    id("com.android.application")
}

android {
    namespace = "com.you1mak.ds4light"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.you1mak.ds4light"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
}
