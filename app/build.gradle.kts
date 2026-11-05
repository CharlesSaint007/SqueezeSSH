plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// CI sets these; local builds fall back to a debug-friendly version.
val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val appVersionName = System.getenv("APP_VERSION_NAME") ?: "0.0.0-local"

// Release signing comes from the environment (CI secrets) or ~/.gradle/gradle.properties.
fun signingValue(name: String): String? =
    System.getenv(name) ?: providers.gradleProperty(name).orNull

android {
    namespace = "me.nick.squeezessh"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.nick.squeezessh"
        minSdk = 26
        targetSdk = 36
        versionCode = ciRunNumber ?: 1
        versionName = appVersionName
    }

    signingConfigs {
        val storeFilePath = signingValue("SIGNING_STORE_FILE")
        if (storeFilePath != null) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = signingValue("SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("SIGNING_KEY_ALIAS")
                keyPassword = signingValue("SIGNING_KEY_PASSWORD")
            }
        }
    }

    flavorDimensions += "channel"
    productFlavors {
        create("stable") {
            dimension = "channel"
        }
        create("dev") {
            dimension = "channel"
            applicationIdSuffix = ".dev"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // VERSION_NAME is recorded in backup files.
        buildConfig = true
    }

    packaging {
        resources {
            // MINA SSHD and BouncyCastle both ship these; they are not needed at runtime.
            excludes += listOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
            )
            // BouncyCastle jars each carry the same license files.
            pickFirsts += listOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":terminal-view"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sshd.core)
    implementation(libs.sshd.putty)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.tink.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // MINA SSHD logs through SLF4J; without a binding it prints a warning at startup.
    implementation(libs.slf4j.nop)
    implementation(libs.androidx.activity.compose)
    // BiometricPrompt, for the app lock and per-host authentication.
    implementation(libs.androidx.biometric)
    // BiometricPrompt needs a FragmentActivity; pin it so it matches the rest of the AndroidX stack.
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
