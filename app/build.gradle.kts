plugins {
    id("com.android.application")
}

android {
    namespace = "com.kocti.crystaldefense"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kocti.crystaldefense"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.ar:core:1.57.0")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")

    testImplementation("junit:junit:4.13.2")
    // В модульных тестах android.jar содержит только заглушки org.json.
    testImplementation("org.json:json:20240303")
}
