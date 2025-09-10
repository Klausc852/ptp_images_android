plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.example.direct_sync_app"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = "27.0.12077973"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.example.direct_sync_app"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = 24 
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    // libaums core library for USB mass storage access
    implementation("me.jahnen.libaums:core:0.10.0")
    
    // Optional modules based on your needs
    // HTTP server module for streaming/sharing USB content
    // implementation("me.jahnen.libaums:httpserver:0.6.2")
    
    // Storage provider module for Storage Access Framework integration
    // implementation("me.jahnen.libaums:storageprovider:0.6.2")
    
    // If you experience USB connectivity issues on Android 9+ devices, consider using the libusb module
    // implementation("me.jahnen.libaums:libusbcommunication:0.4.0")
}
