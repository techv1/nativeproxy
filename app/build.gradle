plugins {
    id 'com.android.application'
}

android {
    namespace 'com.techpremium.miniproxy'
    compileSdk 35

    defaultConfig {
        applicationId "com.techpremium.miniproxy"
        minSdk 26 // Android 8.0 (Ensures basic background channels exist)
        targetSdk 35 // Targets Android 15 capability policies
        versionCode 1
        versionName "1.0"
    }

    buildTypes {
        release {
            minifyEnabled true // Strip out unused Android framework code
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt')
        }
    }
}
