import java.util.Properties

plugins {
    id("com.android.application")
}

/**
 * Release signing, read from `keystore.properties` at the repository root. Both it and the
 * keystore are gitignored, so a fresh clone still builds debug and an unsigned release.
 */
val signingProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "app.flipsilence"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.flipsilence"
        minSdk = 34
        targetSdk = 36
        versionCode = 3
        versionName = "1.1"
    }

    signingConfigs {
        if (signingProps != null) {
            create("release") {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
