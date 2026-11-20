plugins {
    id("com.android.application")
}

val appVersion = rootProject.file("VERSION").readText().trim()
val apkFileName = "Parental-TV-v$appVersion.apk"

android {
    namespace = "com.example.tvparental"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.tvparental"
        minSdk = 28
        targetSdk = 28
        versionCode = 62
        versionName = appVersion
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

dependencies {
    testImplementation("junit:junit:4.13.2")
}

val copyDebugApk by tasks.registering(Copy::class) {
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(layout.buildDirectory.dir("outputs/parental"))
    rename { apkFileName }
}

tasks.configureEach {
    if (name != "assembleDebug") return@configureEach
    finalizedBy(copyDebugApk)
}
