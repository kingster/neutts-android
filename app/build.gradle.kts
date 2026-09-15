plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.github.kingster.neutts"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.github.kingster.neutts"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17 -O3")
                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ABI=arm64-v8a",
                    "-DANDROID_PLATFORM=android-35"
                )
            }
        }
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("NEUTTS_KEYSTORE") ?: "../neutts-release.keystore")
            storePassword = System.getenv("NEUTTS_KEYSTORE_PASS") ?: ""
            keyAlias = System.getenv("NEUTTS_KEY_ALIAS") ?: "neutts_release"
            keyPassword = System.getenv("NEUTTS_KEY_PASS") ?: ""
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isDebuggable = true
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    packagingOptions {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes.addAll(listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE.txt"
            ))
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.onnxruntime.android)

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}