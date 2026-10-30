// Contract test kit for AI plugins: extend ScreeningModelContractTest in a plugin's unit or
// instrumented tests to check it against every rule of the ScreeningModel contract.
plugins {
  id("com.android.library")
  id("kotlin-android")
}

android {
  compileSdk = BuildConfigs.compileSdk
  namespace = "org.smartregister.fhircore.ai.testing"

  defaultConfig { minSdk = BuildConfigs.minSdk }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlinOptions { jvmTarget = JavaVersion.VERSION_17.toString() }

  // The self-test passes a bare ContextWrapper; let android.jar stubs return defaults.
  testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
  api(project(":ai-api"))
  api(libs.junit)
  implementation(libs.kotlinx.coroutines.core)
}
