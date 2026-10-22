plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.basti.wetter"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.basti.wetter"
        minSdk = 26
        targetSdk = 34
        // Auf GitHub Actions steigt die Versionsnummer mit jedem Lauf,
        // damit neue Builds als Update über die alte App installiert werden können.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // Fester Debug-Schlüssel im Repository: Alle Builds sind identisch signiert,
    // sonst würde Android jedes Update wegen "anderer Signatur" ablehnen.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Datencheck läuft ohne Gerät auf der JVM; Android-Aufrufe liefern dort Standardwerte
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.testLogging { events("passed", "failed", "skipped"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }
    }
}

dependencies {
    // Kartenanzeige (OpenStreetMap-Technik) für Radar und Wind
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Datencheck (nur für Tests): JUnit und echtes org.json statt der Android-Platzhalter
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
