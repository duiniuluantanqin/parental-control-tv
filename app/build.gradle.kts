plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.tvparental"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.tvparental"
        minSdk = 28
        targetSdk = 28
        versionCode = 6
        versionName = "0.5.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

}
