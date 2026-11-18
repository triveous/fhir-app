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

import androidx.annotation.StringRes

/**
 * What a class means to the app. The case flow, the result screen's buttons and the analytics work
 * on this, never on class names, so a model with more classes needs no new screens or checks.
 */
enum class AiOutcome {
  /** AI recommends referral. */
  REFER,

  /** AI does not recommend referral. */
  NO_REFERRAL,
}

/** Which result screen a class gets. 2-class models keep today's two screens unchanged. */
enum class ResultScreenStyle {
  LEGACY_SUSPICIOUS,
  LEGACY_NON_SUSPICIOUS,
  BLUE,
  ORANGE,
  RED,
}

/**
 * One output class of a model.
 *
 * @property id stable id used by combine rules, e.g. `low-risk-suspicious`.
 * @property savedValue written to the photo's `ai-result-value` field and to each member's
 *   per-model prediction field. 2-class models keep today's `Suspicious` / `Non-Suspicious`.
 * @property caseValue written to the case's `case-level-ai-result` answer. 2-class models keep
 *   today's `suspicious` / `non-suspicious`.
 * @property severity orders classes from least (0) to most serious; unique within a model.
 * @property labelRes text shown on the photo tile; null keeps showing [savedValue] (2-class).
 */
data class AiClass(
  val id: String,
  val savedValue: String,
  val caseValue: String,
  val outcome: AiOutcome,
  val severity: Int,
  val screen: ResultScreenStyle,
  @StringRes val labelRes: Int? = null,
) {
  val isRefer: Boolean
    get() = outcome == AiOutcome.REFER

  /** Telemetry form of the class, e.g. `non_suspicious`, `low_risk_suspicious`. */
  val telemetryName: String
    get() = id.replace('-', '_')
}

/**
 * One member model of an ensemble.
 *
 * @property id the name rules use (`members`) and telemetry reports as `model_name`.
 * @property resultKeyPrefix result-map prefix of the legacy per-model slot (`model6`, `model8`,
 *   `model82`) the member's result is also passed through; the photo's DocumentReference still
 *   saves results under those slots' `v38-model-v*` URLs.
 * @property resultUrls the URLs the member's result is saved under on the photo's
 *   QuestionnaireResponse answer. Null uses [ModelDescriptor.resultUrlsOf]'s default, which names
 *   the model and the member.
 */
data class ModelMember(
  val id: String,
  val resultKeyPrefix: String,
  val resultUrls: MemberResultUrls? = null,
)

/**
 * The two extensions holding one member's result on a photo's QuestionnaireResponse answer: its
 * class (saved value) and its confidence (percent).
 */
data class MemberResultUrls(val prediction: String, val confidence: String) {
  val both: List<String>
    get() = listOf(prediction, confidence)

  companion object {
    const val BASE = "http://smartregister.org/ai-model-result/"

    /** URLs named after the model and the member, e.g. `.../oral-3class-v1-m1-prediction`. */
    fun named(modelId: String, memberId: String) =
      MemberResultUrls(
        prediction = "$BASE$modelId-$memberId-prediction",
        confidence = "$BASE$modelId-$memberId-confidence",
      )
  }
}

/** How to read one member's raw output. */
sealed interface OutputLayout {
  /** One probability (sigmoid already applied) of [positiveClassId]; strictly above [threshold]. */
  data class SigmoidBinary(
    val positiveClassId: String,
    val negativeClassId: String,
    val threshold: Float = 0.5f,
  ) : OutputLayout

  /**
   * One value per class, in [classOrder]. Highest wins. With [appliedInGraph] the values are
   * already softmax probabilities; without it they are raw scores (logits) and the app applies
   * softmax itself.
   */
  data class Softmax(val classOrder: List<String>, val appliedInGraph: Boolean = true) :
    OutputLayout
}

enum class ChannelOrder {
  BGR,
  RGB,
}

/**
 * What the app does to the photo's pixels before a model with a float input sees them:
 * `value = (pixel * scale - mean[channel]) / std[channel]`, channels in [channelOrder]. [mean] and
 * [std] are listed in [channelOrder]. A model with a uint8 input does its own preprocessing in the
 * graph and must use [RAW_BGR].
 */
data class InputPreprocessing(
  val channelOrder: ChannelOrder = ChannelOrder.BGR,
  val scale: Float = 1f,
  val mean: List<Float> = listOf(0f, 0f, 0f),
  val std: List<Float> = listOf(1f, 1f, 1f),
) {
  init {
    require(mean.size == 3 && std.size == 3) { "mean and std need one value per channel" }
    require(std.none { it == 0f }) { "std must not be 0" }
  }

  companion object {
    /** Pixels exactly as `cv2.imread` gives them: BGR, 0-255. Today's 2-class models. */
    val RAW_BGR = InputPreprocessing()
  }
}

/** How a photo's confidence is worked out from its members. */
enum class ConfidenceMode {
  /** Mean of each member's confidence in its own answer: today's 2-class arithmetic. */
  MEAN_MEMBER_CONFIDENCE,

  /** Mean over members of the probability they give the photo's final class. */
  MEAN_FINAL_CLASS_PROBABILITY,
}

/**
 * Everything the app knows about one model. Ships in the APK; a site only picks a model by [id]
 * (`ai-model.id` on the feature-flags Basic) and can never change what its classes mean.
 *
 * @property telemetryVersion reported as `model_version` on the ONNX path.
 * @property onnxAssets one self-contained `.onnx` file per member, in [members] order, under
 *   `quest/src/main/assets/`. The model can run on ONNX only when every file is bundled.
 * @property pyTorchFallback the model can also run on the legacy PyTorch `.pt` pipeline.
 */
data class ModelDescriptor(
  val id: String,
  val telemetryVersion: String,
  val classes: List<AiClass>,
  val members: List<ModelMember>,
  val outputLayout: OutputLayout,
  val onnxAssets: List<String>,
  val pyTorchFallback: Boolean,
  val defaultRule: CombineRule,
  val confidenceMode: ConfidenceMode,
  val lowConfidencePercent: Float = DEFAULT_LOW_CONFIDENCE_PERCENT,
  val inputPreprocessing: InputPreprocessing = InputPreprocessing.RAW_BGR,
  /**
   * The one output the app reads from each member's `.onnx` file. Null reads every output, in the
   * order the file declares them.
   */
  val onnxOutputName: String? = null,
) {
  init {
    require(classes.isNotEmpty()) { "Model $id declares no classes" }
    require(classes.map { it.id }.toSet().size == classes.size) { "Model $id repeats a class id" }
    require(classes.map { it.severity }.toSet().size == classes.size) {
      "Model $id: every class needs its own severity"
    }
    require(members.isNotEmpty()) { "Model $id declares no members" }
    require(members.map { it.id }.toSet().size == members.size) { "Model $id repeats a member id" }
    require(onnxAssets.isEmpty() || onnxAssets.size == members.size) {
      "Model $id: one ONNX file per member"
    }
    when (outputLayout) {
      is OutputLayout.SigmoidBinary ->
        require(
          classById(outputLayout.positiveClassId) != null &&
            classById(outputLayout.negativeClassId) != null,
        ) {
          "Model $id: sigmoid layout names an unknown class"
        }
      is OutputLayout.Softmax ->
        require(outputLayout.classOrder.toSet() == classes.map { it.id }.toSet()) {
          "Model $id: softmax order must list every class exactly once"
        }
    }
  }

  fun classById(classId: String): AiClass? = classes.firstOrNull { it.id == classId }

  fun memberById(memberId: String): ModelMember? = members.firstOrNull { it.id == memberId }

  /** Where [member]'s result is saved on a photo's QuestionnaireResponse answer. */
  fun resultUrlsOf(member: ModelMember): MemberResultUrls =
    member.resultUrls ?: MemberResultUrls.named(id, member.id)

  /** The least serious class: the default fallback when no class gets enough votes. */
  val leastSevereClass: AiClass
    get() = classes.minBy { it.severity }

  /**
   * Members to run under [rule]: its `members` if set, otherwise all. Always in model order,
   * whatever order the rule lists them in.
   */
  fun activeMembers(rule: CombineRule): List<ModelMember> =
    rule.members?.let { ids -> members.filter { it.id in ids } } ?: members

  companion object {
    /** The V1 launch cut: a result below this confidence % is flagged low-confidence. */
    const val DEFAULT_LOW_CONFIDENCE_PERCENT = 65f
  }
}
