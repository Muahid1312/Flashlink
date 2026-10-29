import java.util.Properties

plugins {
    id("com.android.application")
    id("dev.flutter.flutter-gradle-plugin")
}
val releasePropertiesFile = rootProject.file("key.properties")
val releaseProperties = Properties().apply {
    if(releasePropertiesFile.isFile) releasePropertiesFile.inputStream().use { load(it) }
}
val releaseSigningReady = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all {
    !releaseProperties.getProperty(it).isNullOrBlank()
} && rootProject.file(releaseProperties.getProperty("storeFile") ?: ".missing-release-keystore").isFile

gradle.taskGraph.whenReady {
    if(allTasks.any { it.path in setOf(":app:assembleRelease", ":app:bundleRelease", ":app:packageRelease") } && !releaseSigningReady) {
        throw GradleException("Release signing is not configured. Supply ignored android/key.properties with storeFile, storePassword, keyAlias and keyPassword, or build a debug APK for testing. No release key is generated automatically.")
    }
}

android {
    namespace = "com.flashlink.app"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    if(releaseSigningReady) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(releaseProperties.getProperty("storeFile"))
            storePassword = releaseProperties.getProperty("storePassword")
            keyAlias = releaseProperties.getProperty("keyAlias")
            keyPassword = releaseProperties.getProperty("keyPassword")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
    defaultConfig {
        applicationId = "com.flashlink.app"
        minSdk = 24
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }
}
kotlin {
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
}
flutter { source = "../.." }

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
