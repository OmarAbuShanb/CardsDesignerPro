import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
}

val keystorePropertiesFile: File = rootProject.file("key.properties")
val keystoreProperties = Properties()
keystoreProperties.load(keystorePropertiesFile.inputStream())

android {
    namespace = "dev.anonymous.cardsdesignerpro.app"
    compileSdk = 36
    compileSdkExtension = 19

    defaultConfig {
        applicationId = "dev.anonymous.cardsdesignerpro.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProperties["STORE_FILE"] as String)
            storePassword = keystoreProperties["STORE_PASSWORD"] as String
            keyAlias = keystoreProperties["KEY_ALIAS"] as String
            keyPassword = keystoreProperties["KEY_PASSWORD"] as String
        }
    }

    /*splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }*/

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)

    // Lifecycle
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // CSV / Excel / PDF
    implementation(libs.opencsv)
    implementation(libs.fastexcel.reader)
    implementation(libs.aalto.xml)       // StAX XML parser (fastexcel transitive dep, explicit for Android)
    implementation(libs.stax2.api)       // StAX2 API (not in Android runtime)
    implementation(libs.stax.api)        // javax.xml.stream (not in Android runtime unlike JDK)
    implementation(libs.pdfbox.android)

    // QR Code generation — custom-qr-generator (powered by ZXing internally, adds shape/logo/color support)
    implementation(libs.custom.qr.generator)
    
    // Modern pure Kotlin 16KB safe PDF Viewer (For Android < 12)
    implementation(libs.afreakyelf.pdf.viewer)
    
    // Jetpack PDF Viewer (For Android 12+)
    implementation(libs.androidx.pdf.viewer)

    // Color picker
    implementation(libs.colorpickerview)

    // PDF viewer dependency removed (using native PdfRenderer)

    // Image loading
    implementation(libs.coil)
    
    // SVG Parsing
    implementation(libs.androidsvg.aar)

    // Splash Screen API (backport to API 26+)
    implementation(libs.androidx.core.splashscreen)

    // Firebase Cloud Functions (trial registration)
    implementation(libs.firebase.functions)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}