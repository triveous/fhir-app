/*
 * Copyright 2026 Indian Institute of Science (IISc)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.smartregister.fhircore.quest.ml

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.smartregister.fhircore.engine.util.AiModelConfig
import org.smartregister.fhircore.quest.camerax.CameraxLauncherFragment
import org.w3c.dom.Element
import timber.log.Timber

/**
 * Checks that the app gives the same 3-class results as the ML team's server run, on the photos and
 * expected results they sent (`docs/qa/images_3_class_model_testing`).
 *
 * Nothing of the app is replaced between the photo file and the saved result. Each photo goes
 * through the call the camera screen makes after the shutter, [OnnxScreeningPipeline.process]:
 * OpenCV decode and resize, the model's preprocessing, the bundled `.onnx` files, softmax and the
 * combine rule, with the site's setting resolved by [AiModelSelector]. Only Android's asset loading
 * and the feature-flag source are faked. OpenCV and ONNX Runtime are the desktop builds of the
 * versions the app ships.
 *
 * Skipped where the photos, the models (neither is in git) or the native libraries are missing.
 * Every run writes what the app answered to `quest/build/reports/three-class-server-parity.csv`.
 */
@RunWith(Parameterized::class)
class ThreeClassServerParityTest(private val photo: ServerResult) {

  @Before
  fun requireInputs() {
    assumeTrue("The ML team's photos and sheet are not in ${ServerSheet.FOLDER}", ServerSheet.isPresent)
    assumeTrue("The 3-class model files are not in assets", AppUnderTest.modelsPresent)
    assumeTrue(
      "ONNX Runtime has no native library for this JVM (${System.getProperty("os.arch")}); use an arm64 JDK",
      AppUnderTest.onnxRuntimeAvailable,
    )
    assumeTrue("Desktop OpenCV is not on java.library.path; run through Gradle", AppUnderTest.openCvAvailable)
  }

  @Test
  fun `model 1 gives the server's class`() = assertMember("m1", "model6", photo.model1)

  @Test
  fun `model 2 gives the server's class`() = assertMember("m2", "model8", photo.model2)

  @Test
  fun `both models combined give the server's class`() {
    val app = AppUnderTest.screen(photo, members = null)
    assertEquals(
      "Combined class of ${photo.id} (app: $app)",
      className(photo.combined),
      app.prediction,
    )
  }

  /** The member's answer, on a site that runs only it and as saved on a site that runs both. */
  private fun assertMember(member: String, resultKeyPrefix: String, expected: Int) {
    val alone = AppUnderTest.screen(photo, members = member)
    assertEquals("$member alone on ${photo.id} (app: $alone)", className(expected), alone.prediction)

    val both = AppUnderTest.screen(photo, members = null)
    assertEquals(
      "$member with both models running on ${photo.id} (app: $both)",
      className(expected),
      both.memberPrediction(resultKeyPrefix),
    )
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun photos(): List<ServerResult> =
      // One placeholder, so a checkout without the photos reports skipped tests, not none.
      if (ServerSheet.isPresent) ServerSheet.rows else listOf(ServerResult("no photos", -1, -1, -1))

    @JvmStatic @AfterClass fun writeReport() = AppUnderTest.writeReport()
  }
}

/** Checks on the ML team's sheet that need no model run. */
class ThreeClassServerSheetTest {

  @Before
  fun requireSheet() {
    assumeTrue("The ML team's photos and sheet are not in ${ServerSheet.FOLDER}", ServerSheet.isPresent)
  }

  @Test
  fun `every sheet row has exactly one photo and every photo a row`() {
    ServerSheet.rows.forEach { assertEquals("Photos named ${it.id}", 1, ServerSheet.photosOf(it).size) }
    assertEquals(
      "Photos with no row in the sheet",
      emptyList<String>(),
      ServerSheet.photos.filter { file -> ServerSheet.rows.none { file in ServerSheet.photosOf(it) } }.map { it.name },
    )
  }

  @Test
  fun `the sheet covers every pair of model answers`() {
    val pairs = ServerSheet.rows.map { it.model1 to it.model2 }.toSet()
    assertEquals((0..2).flatMap { m1 -> (0..2).map { m2 -> m1 to m2 } }.toSet(), pairs)
  }

  @Test
  fun `the app's combine table turns the server's model answers into the server's combined class`() {
    val model = ModelCatalog.THREE_CLASS
    ServerSheet.rows.forEach {
      val combined =
        CombineRuleEvaluator.decidePhoto(
          model.classes,
          listOf(className(it.model1), className(it.model2)),
          model.defaultRule.photo,
        )
      assertEquals("${it.id}: m1=${it.model1} m2=${it.model2}", className(it.combined), combined.id)
    }
  }

  /** Every case in the sheet has one photo, so the sheet says nothing about cases with several. */
  @Test
  fun `the app's case rule keeps the server's combined class for the sheet's one-photo cases`() {
    val cases = ServerSheet.photos.groupBy { it.name.substringBefore('_') }
    assertEquals("Cases with several photos", emptyMap<String, List<File>>(), cases.filterValues { it.size > 1 })

    val model = ModelCatalog.THREE_CLASS
    ServerSheet.rows.forEach {
      val case = CombineRuleEvaluator.decide(model.classes, listOf(className(it.combined)), model.defaultRule.case)
      assertEquals("Case of ${it.id}", className(it.combined), case.id)
    }
  }
}

/** One row of the sheet: the server's class numbers for one photo. */
data class ServerResult(val id: String, val model1: Int, val model2: Int, val combined: Int) {
  override fun toString() = id
}

/** The sheet's class numbers, as the ML team's combine table defines them. */
private fun className(number: Int): String =
  when (number) {
    0 -> ModelCatalog.NON_SUSPICIOUS
    1 -> ModelCatalog.LOW_RISK_SUSPICIOUS
    2 -> ModelCatalog.HIGH_RISK_SUSPICIOUS
    else -> throw IllegalArgumentException("The sheet has class $number; expected 0, 1 or 2")
  }

/** The ML team's photos and expected results. */
private object ServerSheet {
  const val FOLDER = "docs/qa/images_3_class_model_testing"
  private const val SHEET = "ImageIDs_for_3-class_model_testing.xlsx"

  /** Tests run in `quest/` under Gradle, and in `android/` under some IDE setups. */
  private val folder: File? = listOf(File("../$FOLDER"), File(FOLDER)).firstOrNull { it.isDirectory }

  val photos: List<File> =
    folder?.listFiles { file -> file.extension.lowercase() in setOf("jpg", "jpeg", "png") }.orEmpty().sorted()

  val isPresent: Boolean = photos.isNotEmpty() && File(folder, SHEET).isFile

  val rows: List<ServerResult> by lazy { read(File(folder, SHEET)) }

  /** Photo names are `<case id>_<photo id>_<time>.jpg`; the sheet lists the photo id. */
  fun photosOf(row: ServerResult): List<File> = photos.filter { row.id in it.nameWithoutExtension.split('_') }

  /** Columns A–D of the first worksheet, below the header row. An .xlsx is a zip of XML parts. */
  private fun read(xlsx: File): List<ServerResult> =
    ZipFile(xlsx).use { zip ->
      fun elements(part: String, tag: String): List<Element> =
        zip.getInputStream(zip.getEntry(part)).use { stream ->
          DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream).documentElement.children(tag)
        }
      val strings = elements("xl/sharedStrings.xml", "si").map { it.textContent }
      val table =
        elements("xl/worksheets/sheet1.xml", "row").map { row ->
          row.children("c").associate { cell ->
            val value = cell.children("v").firstOrNull()?.textContent
            val isText = cell.getAttribute("t") == "s"
            cell.getAttribute("r").takeWhile { it.isLetter() } to
              if (isText) value?.let { strings[it.toInt()] } else value
          }
        }
      check(table.first()["B"] == "predicted_class_model1" && table.first()["C"] == "predicted_class_model2") {
        "Unexpected columns in $xlsx: ${table.first()}"
      }
      check(table.first()["D"] == "combined_predicted_class") { "Unexpected columns in $xlsx: ${table.first()}" }
      fun Map<String, String?>.classNumber(column: String): Int =
        checkNotNull(this[column]) { "No value in column $column of $this" }.toDouble().toInt()
      table.drop(1).filter { !it["A"].isNullOrBlank() }.map {
        ServerResult(it["A"]!!.trim(), it.classNumber("B"), it.classNumber("C"), it.classNumber("D"))
      }
    }

  private fun Element.children(tag: String): List<Element> =
    getElementsByTagName(tag).let { nodes -> List(nodes.length) { nodes.item(it) as Element } }
}

/** The app's photo pipeline, with `src/main/assets` standing in for the APK's assets. */
private object AppUnderTest {

  private val assets: File = listOf(File("src/main/assets"), File("quest/src/main/assets")).first { it.isDirectory }

  val modelsPresent: Boolean = ModelCatalog.THREE_CLASS.onnxAssets.all { File(assets, it).isFile }

  // UnsatisfiedLinkError is an Error, which runCatching also catches.
  val onnxRuntimeAvailable: Boolean = runCatching { OrtEnvironment.getEnvironment() }.isSuccess

  val openCvAvailable: Boolean =
    System.getProperty("java.library.path").orEmpty().split(File.pathSeparator).any {
      File(it, System.mapLibraryName("opencv_java4")).isFile
    }

  /** The pipeline reports failures instead of throwing them; kept to explain a missing result. */
  private var lastError: Throwable? = null

  private val engine: OnnxInferenceEngine by lazy {
    Timber.plant(
      object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
          if (t != null) lastError = t
        }
      },
    )
    val context = mockk<Context>()
    every { context.assets.list("") } returns assets.list()
    every { context.assets.open(any()) } answers { File(assets, firstArg<String>()).inputStream() }
    OnnxInferenceEngine(context)
  }
  private val pipeline by lazy { OnnxScreeningPipeline(engine) }
  private val selector by lazy { AiModelSelector(mockk(), engine) }

  private val answers = linkedMapOf<Pair<ServerResult, String?>, Answer>()

  /** What the app saves for [photo] on a site set to the 3-class model and [members] (null = both). */
  fun screen(photo: ServerResult, members: String?): Answer =
    answers.getOrPut(photo to members) {
      val selection = selector.select(AiModelConfig(ModelCatalog.THREE_CLASS_MODEL_ID, members = members))
      check(selection is AiModelSelection.Ready && selection.active.ignoredSetting == null) { "$selection" }
      lastError = null
      val result =
        pipeline.process(
          active = selection.active,
          absolutePath = ServerSheet.photosOf(photo).single().absolutePath,
          screeningId = null,
          runAiInference = true,
        )
      assertNotNull("The app could not process ${photo.id}: $lastError", result)
      Answer(result!!.resultMap)
    }

  fun writeReport() {
    val both = answers.filterKeys { (_, members) -> members == null }
    if (both.isEmpty()) return
    val lines =
      both.map { (key, app) ->
        val server = key.first
        val expected = listOf(server.model1, server.model2, server.combined).map(::className)
        val actual = listOf(app.memberPrediction("model6"), app.memberPrediction("model8"), app.prediction)
        val matches = expected.zip(actual).map { (a, b) -> a == b }
        val cells =
          listOf(server.id) +
            (0..2).flatMap { listOf(expected[it], actual[it], if (matches[it]) "match" else "MISMATCH") } +
            listOf(app.memberConfidence("model6"), app.memberConfidence("model8"))
        matches to cells.joinToString(",")
      }
    val header =
      "photo,m1_server,m1_app,m1,m2_server,m2_app,m2,combined_server,combined_app,combined,m1_confidence,m2_confidence"
    val report = File(if (File("src/main/assets").isDirectory) "build/reports" else "quest/build/reports")
    report.mkdirs()
    File(report, "three-class-server-parity.csv").writeText((listOf(header) + lines.map { it.second }).joinToString("\n", postfix = "\n"))
    val score = (0..2).map { column -> lines.count { it.first[column] } }
    println("App vs server, matching photos of ${lines.size}: m1=${score[0]} m2=${score[1]} combined=${score[2]}")
  }

  /** The result map the camera screen saves from. */
  class Answer(private val resultMap: Map<String, Any>) {
    val prediction: Any? = resultMap[CameraxLauncherFragment.CAMERA_PREDICTION_KEY]

    fun memberPrediction(prefix: String): Any? = resultMap["${prefix}_prediction"]

    fun memberConfidence(prefix: String): Any? = resultMap["${prefix}_confidence"]

    override fun toString() =
      listOf("m1" to "model6", "m2" to "model8")
        .filter { (_, prefix) -> memberPrediction(prefix) != null }
        .joinToString { (id, prefix) -> "$id=${memberPrediction(prefix)} ${memberConfidence(prefix)}%" }
  }
}
