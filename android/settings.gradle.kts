pluginManagement {
    repositories {
        google()
        maven(url = "https://plugins.gradle.org/m2/")
        maven(url = "https://oss.sonatype.org/content/repositories/snapshots/")
    }
}
rootProject.name = "fhircore-android"
include (":engine")
include (":quest")
include (":geowidget")
include(":linting")
include(":macrobenchmark")

// AI plug-and-play (docs/ai-model-integration-guide.md). The contract and its test kit are always
// built; every folder under ai-plugins/ that has a build file is included as :ai-plugins:<name>.
// Including a plugin here only makes it buildable — the app picks it up with -PaiPlugins=<name>.
include(":ai-api")
include(":ai-api-testing")
file("ai-plugins")
  .listFiles { dir -> dir.isDirectory && File(dir, "build.gradle.kts").isFile }
  ?.sortedBy { it.name }
  ?.forEach { include(":ai-plugins:${it.name}") }
