// The contract between the app and a screening model. This is the only module an AI plugin
// compiles against, so it must stay free of app types (FHIR, Hilt, OpenCV, quest/engine classes).
plugins {
  id("com.android.library")
  id("kotlin-android")
}

android {
  compileSdk = BuildConfigs.compileSdk
  namespace = "org.smartregister.fhircore.ai"

  defaultConfig {
    minSdk = BuildConfigs.minSdk
    consumerProguardFiles("consumer-rules.pro")
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlinOptions { jvmTarget = JavaVersion.VERSION_17.toString() }
}

dependencies { testImplementation(libs.junit) }
