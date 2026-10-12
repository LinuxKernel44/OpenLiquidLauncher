import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: read from keystore.properties (path in KEYSTORE_PROPERTIES, default ~/openliquidlauncher-signing/keystore.properties)
// or from CI secrets (SIGNING_STORE_FILE / SIGNING_STORE_PASSWORD / SIGNING_KEY_ALIAS / SIGNING_KEY_PASSWORD).
// Nothing secret is stored in the repository. Without credentials the release falls back to the debug key.
val signingProps = Properties().apply {
    val f = file(System.getenv("KEYSTORE_PROPERTIES") ?: "${System.getProperty("user.home")}/openliquidlauncher-signing/keystore.properties")
    if (f.exists()) f.inputStream().use(::load)
    System.getenv("SIGNING_STORE_FILE")?.let { setProperty("storeFile", it) }
    System.getenv("SIGNING_STORE_PASSWORD")?.let { setProperty("storePassword", it) }
    System.getenv("SIGNING_KEY_ALIAS")?.let { setProperty("keyAlias", it) }
    System.getenv("SIGNING_KEY_PASSWORD")?.let { setProperty("keyPassword", it) }
}
val hasReleaseSigning = signingProps.getProperty("storeFile")?.let { file(it).exists() } == true

android {
    namespace = "com.linuxkernel44.openliquidlauncher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.linuxkernel44.openliquidlauncher"
        minSdk = 33
        targetSdk = 36
        versionCode = 60
        versionName = "1.16.6"
        androidResources.localeFilters += arrayOf("en")
    }

    signingConfigs {
        if (hasReleaseSigning) create("release") {
            storeFile = file(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasReleaseSigning) "release" else "debug")
        }
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += arrayOf("DebugProbesKt.bin", "kotlin-tooling-metadata.json", "META-INF/*.version")
        }
    }
    lint {
        checkReleaseBuilds = false
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)?.outputFileName?.set(
                "OpenLiquidLauncher-${variant.outputs.first().versionName.get()}.apk",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material.ripple)
    // The liquid glass 'backdrop' library is vendored in src/main/java/com/kyant/backdrop (adds a render-resolution scale).
    implementation("org.jetbrains:annotations:26.1.0")
    implementation(libs.kyant.shapes)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
