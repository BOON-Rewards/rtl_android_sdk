plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.affinaloyalty.rtlsdk.example"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.affinaloyalty.rtlsdk.example"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "sdkFeatures"
    productFlavors {
        create("core") {
            dimension = "sdkFeatures"
            applicationIdSuffix = ".core"
            versionNameSuffix = "-core"
            manifestPlaceholders["appScheme"] = "rtlsdkcoreexample"
            buildConfigField("String", "RTL_APP_SCHEME", "\"rtlsdkcoreexample\"")
        }
        create("hyperlocalOffers") {
            dimension = "sdkFeatures"
            applicationIdSuffix = ".hyperlocaloffers"
            versionNameSuffix = "-hyperlocal-offers"
            manifestPlaceholders["appScheme"] = "rtlsdkexample"
            buildConfigField("String", "RTL_APP_SCHEME", "\"rtlsdkexample\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
    "hyperlocalOffersImplementation"(project(":hyperlocal-offers"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
