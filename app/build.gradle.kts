import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/** Secrets stay in local.properties, which git ignores. */
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun localProp(key: String) = (localProps.getProperty(key) ?: System.getenv(key.uppercase().replace('.', '_')) ?: "").trim()

android {
    namespace = "com.artemkhateev.hinnerjag"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.artemkhateev.hinnerjag"
        // app-automotive requires API 29; the AAOS emulators here are API 34 and 35.
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        // Västtrafik "Planera Resa v4" app credentials (developer.vasttrafik.se). Empty → invented times.
        buildConfigField("String", "VASTTRAFIK_CLIENT_ID", "\"${localProp("vasttrafik.clientId")}\"")
        buildConfigField("String", "VASTTRAFIK_CLIENT_SECRET", "\"${localProp("vasttrafik.clientSecret")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.automotive)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
