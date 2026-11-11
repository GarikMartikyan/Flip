import java.util.Properties

plugins {
    id("com.android.application")
}

/**
 * Release signing, read from `~/signing-keys/flip/keystore.properties`, the one place on this Mac
 * that holds the keys; `storeFile` in it is relative to that folder. Nothing of it is in the repo,
 * so a fresh clone elsewhere still builds debug and an unsigned release.
 */
val signingDir = File(System.getProperty("user.home"), "signing-keys/flip")
val signingProps = File(signingDir, "keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "app.flipsilence"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.flipsilence"
        minSdk = 34
        targetSdk = 36
        versionCode = 7
        versionName = "1.3.2"
    }

    signingConfigs {
        if (signingProps != null) {
            create("release") {
                storeFile = File(signingDir, signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // R8 shrinks, optimizes and obfuscates the release; Play Vitals flags apps without it
            // for memory use from Feb 2027. The mapping file travels inside the AAB.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Same code as release, debug-signed and installable next to the Play build, so the
        // minified app can be tried on a phone without uninstalling the real one.
        create("r8check") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".r8check"
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
