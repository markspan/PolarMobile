import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional local liblsl checkout (gradle property or local.properties key "liblsl.dir").
// When empty, CMake downloads the pinned liblsl release itself.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val liblslDir: String = (findProperty("liblsl.dir") as String?)
    ?: localProps.getProperty("liblsl.dir")
    ?: ""
// Release signing key: environment variable, else local.properties key; empty counts as unset.
fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotEmpty() } ?: localProps.getProperty(prop)?.takeIf { it.isNotEmpty() }
val releaseKeystore: String? = signingValue("POLARMOBILE_KEYSTORE", "polarmobile.keystore")
// Comma separated ABI list, e.g. -Pabis=arm64-v8a for a faster single-ABI build.
val abiList: List<String> = ((findProperty("abis") as String?) ?: "arm64-v8a,armeabi-v7a,x86_64")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }

android {
    namespace = "io.github.markspan.polarmobile"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "io.github.markspan.polarmobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += abiList
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DLIBLSL_SOURCE_DIR=${liblslDir.replace('\\', '/')}",
                )
                cppFlags += "-std=c++17"
            }
        }
    }

    signingConfigs {
        // Release signing from environment variables (CI) or local.properties (local builds);
        // falls back to the debug key so that `assembleRelease` always yields an installable APK.
        create("release") {
            if (releaseKeystore != null) {
                storeFile = file(releaseKeystore)
                storePassword = signingValue("POLARMOBILE_KEYSTORE_PASSWORD", "polarmobile.keystore.password")
                keyAlias = signingValue("POLARMOBILE_KEY_ALIAS", "polarmobile.key.alias")
                keyPassword = signingValue("POLARMOBILE_KEY_PASSWORD", "polarmobile.key.password")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeystore != null)
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

base {
    archivesName.set("PolarMobile-${android.defaultConfig.versionName}")
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
