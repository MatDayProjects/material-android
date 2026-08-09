plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

val generatedNativeRuntimeRoot = layout.buildDirectory.dir("generated/native-runtime")
val suppliedNativeRuntime = providers.environmentVariable("OPENVM_QEMU_RUNTIME_DIR")

val prepareNativeQemuRuntime by tasks.registering {
    inputs.dir(suppliedNativeRuntime).optional()
    outputs.dir(generatedNativeRuntimeRoot)
    doLast {
        val destination = generatedNativeRuntimeRoot.get().asFile
        destination.deleteRecursively()
        val source = suppliedNativeRuntime.orNull?.let(::file)
        if (source == null || !source.isDirectory) return@doLast

        source.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name in setOf("arm64-v8a", "x86_64") }
            .forEach { abiDirectory ->
                copy {
                    from(abiDirectory) {
                        include("libopenvm-qemu-*.so")
                    }
                    into(destination.resolve("jniLibs/${abiDirectory.name}"))
                }
                copy {
                    from(abiDirectory.resolve("lib")) {
                        include("*.so")
                        include("*.so.*")
                    }
                    into(destination.resolve("assets/native-qemu/${abiDirectory.name}/lib"))
                }
                copy {
                    from(abiDirectory.resolve("share"))
                    into(destination.resolve("assets/native-qemu/${abiDirectory.name}/share"))
                }
            }
    }
}

tasks.named("preBuild") { dependsOn(prepareNativeQemuRuntime) }

android.sourceSets["main"].jniLibs.srcDir(generatedNativeRuntimeRoot.map { it.dir("jniLibs") })
android.sourceSets["main"].assets.srcDir(generatedNativeRuntimeRoot.map { it.dir("assets") })

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
        testInstrumentationRunnerArguments["requireNativeRuntime"] =
            providers.gradleProperty("openvmRequireNativeRuntime").orNull ?: "false"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            // Android Gradle Plugin otherwise injects its generated Android Debug
            // identity. OpenVM artifacts are deliberately unsigned in every variant.
            signingConfig = null
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = null
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

val verifyUnsignedBuildTypes by tasks.registering {
    doLast {
        listOf("debug", "release").forEach { buildTypeName ->
            check(android.buildTypes.getByName(buildTypeName).signingConfig == null) {
                "OpenVM $buildTypeName artifacts must remain unsigned"
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(verifyUnsignedBuildTypes) }

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
