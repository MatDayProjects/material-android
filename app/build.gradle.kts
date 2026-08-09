plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

val ciReleaseKeystorePath = providers.environmentVariable("OPENVM_RELEASE_KEYSTORE").orNull
val ciReleaseStorePassword = providers.environmentVariable("OPENVM_RELEASE_STORE_PASSWORD").orNull
val ciReleaseKeyAlias = providers.environmentVariable("OPENVM_RELEASE_KEY_ALIAS").orNull
val ciReleaseKeyPassword = providers.environmentVariable("OPENVM_RELEASE_KEY_PASSWORD").orNull
val ciReleaseSigningValues = listOf(
    ciReleaseKeystorePath,
    ciReleaseStorePassword,
    ciReleaseKeyAlias,
    ciReleaseKeyPassword,
)
val ciReleaseSigningConfigured = ciReleaseSigningValues.all { it != null }
if (ciReleaseSigningValues.any { it != null } && !ciReleaseSigningConfigured) {
    error("CI release signing requires OPENVM_RELEASE_KEYSTORE, OPENVM_RELEASE_STORE_PASSWORD, OPENVM_RELEASE_KEY_ALIAS, and OPENVM_RELEASE_KEY_PASSWORD together.")
}

android {
    namespace = "org.openvm.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.openvm.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (ciReleaseSigningConfigured) {
            create("ciRelease") {
                storeFile = file(ciReleaseKeystorePath!!)
                storePassword = ciReleaseStorePassword
                keyAlias = ciReleaseKeyAlias
                keyPassword = ciReleaseKeyPassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (ciReleaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("ciRelease")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.google.material)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)
}
