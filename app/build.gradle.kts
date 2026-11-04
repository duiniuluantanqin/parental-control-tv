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
        versionCode = 23
        versionName = "0.9.3"
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

val copyDebugApk by tasks.registering(Copy::class) {
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(layout.buildDirectory.dir("outputs/parental"))
    rename { "Parental-TV.apk" }
}

tasks.configureEach {
    if (name != "assembleDebug") return@configureEach
    finalizedBy(copyDebugApk)
}
