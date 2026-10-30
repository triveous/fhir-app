// A runnable example of an AI plugin (see docs/ai-model-integration-guide.md): its own
// pre-processing, its own decision, registered with Hilt, checked by the contract test kit.
// It is a DEMO — a colour heuristic, NOT a medical model — and is never in a build unless asked
// for with -PaiPlugins=sample-code-plugin. Copy this folder to start a real plugin.
plugins {
  id("com.android.library")
  id("kotlin-android")
  id("kotlin-kapt")
}

android {
  compileSdk = BuildConfigs.compileSdk
  namespace = "org.smartregister.fhircore.ai.sample"
  defaultConfig { minSdk = BuildConfigs.minSdk }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlinOptions { jvmTarget = JavaVersion.VERSION_17.toString() }

  testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
  // The only app module a plugin may depend on.
  implementation(project(":ai-api"))
  implementation(libs.dagger.hilt.android)
  kapt(libs.dagger.hilt.compiler)

  testImplementation(project(":ai-api-testing"))
  testImplementation(libs.robolectric)
}
