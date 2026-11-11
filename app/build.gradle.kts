plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}
android {
    namespace = "io.github.alita23333.wattscope"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.alita23333.wattscope"
        minSdk = 31; targetSdk = 36
        versionCode = 1; versionName = "0.1.0"
        buildConfigField("boolean", "VALIDATION", "false")
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild { cmake { cppFlags += "-std=c++20"; arguments += "-DANDROID_STL=c++_static" } }
    }
    ndkVersion = "28.2.13676358"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    buildFeatures { compose = true; buildConfig = true }
    lint {
        // Lifecycle 2.9's optional LiveData detector has a UAST ABI mismatch with AGP 8.7.
        // This app uses StateFlow, not MutableLiveData. Keep every applicable release check enabled.
        disable += "NullSafeMutableLiveData"
    }
    packaging { jniLibs.useLegacyPackaging = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
        create("benchmark") {
            initWith(getByName("release"))
            buildConfigField("boolean", "VALIDATION", "true")
            matchingFallbacks += listOf("release")
        }
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.compose.ui:ui:1.8.2")
    implementation("androidx.compose.foundation:foundation:1.8.2")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("top.yukonga.miuix.kmp:miuix:0.4.3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    kapt("androidx.room:room-compiler:2.7.1")
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
