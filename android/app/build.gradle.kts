import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Release signing: reads android/key.properties when present (created by
// the GitHub Actions workflow from secrets, or by hand for local builds).
// Without it the release build falls back to the debug keys so
// `flutter run --release` keeps working — but a debug-signed APK triggers
// stronger Play Protect warnings, so published releases should always be
// signed with the release key. NEVER commit key.properties or the
// keystore — both are gitignored (see .gitignore).
val keyProperties = Properties()
val keyPropertiesFile = rootProject.file("key.properties")
if (keyPropertiesFile.exists()) {
    keyProperties.load(FileInputStream(keyPropertiesFile))
}
val hasReleaseSigning = keyPropertiesFile.exists() &&
    keyProperties["storeFile"] != null &&
    keyProperties["storePassword"] != null &&
    keyProperties["keyAlias"] != null &&
    keyProperties["keyPassword"] != null

// Experimental CloudStream (.cs3) runtime. OFF by default so the normal
// build never depends on it; enable with STREAMBOX_CS3=1 (the "Build APK"
// workflow has a checkbox for it). See android/app/src/cs3/.
val withCs3 = System.getenv("STREAMBOX_CS3") == "1"
val cs3LibraryVersion = System.getenv("STREAMBOX_CS3_LIB") ?: "v4.8.0"

android {
    namespace = "com.kizoo.streambox"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Android Lint crashes on the video_player_android plugin during
        // release builds (:video_player_android:lintVitalAnalyzeRelease fails
        // with a NoSuchMethodError inside lint's own JavaDocParser — a bug in
        // lint, not in our code). Disable release lint checks so the APK
        // build is not blocked by it.
        checkReleaseBuilds = false
        abortOnError = false
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.kizoo.streambox"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        // Uses the version code from pubspec.yaml. When using split APKs, 1000 * ABI_VERSION
        // is added automatically by Flutter. (https://developer.android.com/studio/build/configure-apk-splits#configure-APK-versions)
        // You can force using the value of versionCode by specifying the `-P force-version-code-ignoring-abi=true`
        // flag during build.
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    sourceSets {
        if (withCs3) {
            getByName("main").java.srcDir("src/cs3/kotlin")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keyProperties["storeFile"] as String)
                storePassword = keyProperties["storePassword"] as String
                keyAlias = keyProperties["keyAlias"] as String
                keyPassword = keyProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            // Release-signed when key.properties is present, debug-signed
            // otherwise (local runs without the keystore keep working).
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    if (withCs3) {
        // CloudStream's plugin API (MainAPI, extractors, NiceHttp client).
        implementation("com.github.recloudstream.cloudstream:library:$cs3LibraryVersion")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    }
}

flutter {
    source = "../.."
}
